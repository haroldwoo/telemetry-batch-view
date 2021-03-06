package com.mozilla.telemetry.views

import com.mozilla.telemetry.experiments.Permutations.weightedGenerator
import com.mozilla.telemetry.experiments.analyzers._
import com.mozilla.telemetry.metrics._
import com.mozilla.telemetry.utils.getOrCreateSparkSession
import org.apache.spark.sql.functions.{col, min, udf}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.storage.StorageLevel
import org.rogach.scallop.ScallopConf

import scala.util.{Failure, Success, Try}

object ExperimentAnalysisView {
  def defaultErrorAggregatesBucket = "net-mozaws-prod-us-west-2-pipeline-data"
  def errorAggregatesPath = "error_aggregates/v2"
  def jobName = "experiment_analysis"
  def schemaVersion = "v1"
  // This gives us ~120MB partitions with the columns we have now. We should tune this as we add more columns.
  def rowsPerPartition = 25000

  private val logger = org.apache.log4j.Logger.getLogger(this.getClass.getSimpleName)

  // scalar_ and histogram_ columns are automatically added
  private val usedColumns = List(
    "client_id",
    "experiment_id",
    "experiment_branch"
  )

  implicit class ExperimentDataFrame(df: DataFrame) {
    def selectUsedColumns: DataFrame = {
      val columns = (usedColumns
      ++ (df.schema.map(_.name).filter { s: String => s.startsWith("histogram_") || s.startsWith("scalar_") }))
      df.select(columns.head, columns.tail: _*)
    }
  }

  // Configuration for command line arguments
  class Conf(args: Array[String]) extends ScallopConf(args) {
    // TODO: change to s3 bucket/keys
    val inputLocation = opt[String]("input", descr = "Source for parquet data", required = true)
    val outputLocation = opt[String]("output", descr = "Destination for parquet data", required = true)
    val errorAggregatesBucket = opt[String](descr = "Bucket for error_aggregates data", required = false,
      default = Some(defaultErrorAggregatesBucket))
    val metric = opt[String]("metric", descr = "Run job on just this metric", required = false)
    val experiment = opt[String]("experiment", descr = "Run job on just this experiment", required = false)
    val date = opt[String]("date", descr = "Run date for this job (defaults to yesterday)", required = false)
    verify()
  }

  def getSpark: SparkSession = {
    val spark = getOrCreateSparkSession(jobName)
    if (spark.sparkContext.defaultParallelism > 200) {
      spark.sqlContext.sql(s"set spark.sql.shuffle.partitions=${spark.sparkContext.defaultParallelism}")
    }
    spark.sparkContext.setLogLevel("INFO")
    spark
  }

  def main(args: Array[String]) {
    val conf = new Conf(args)
    val sparkSession = getSpark

    val experimentData = sparkSession.read.option("mergeSchema", "true").parquet(conf.inputLocation())
    val date = getDate(conf)
    logger.info("=======================================================================================")
    logger.info(s"Starting $jobName for date $date")

    val experiments = getExperiments(conf, experimentData)
    sparkSession.stop()

    logger.info(s"List of experiments to process for $date is: $experiments")

    experiments.foreach{ e: String =>
      logger.info(s"Aggregating pings for experiment $e")

      val spark = getSpark

      val experimentsSummary = spark.read.option("mergeSchema", "true").parquet(conf.inputLocation())
        .where(col("experiment_id") === e)

      val minDate = experimentsSummary
        .agg(min("submission_date_s3"))
        .first.getAs[String](0)

      val errorAggregates = Try(spark.read.parquet(s"s3://${conf.errorAggregatesBucket()}/$errorAggregatesPath")) match {
        case Success(df) => df.where(col("experiment_id") === e && col("submission_date") >= minDate)
        case Failure(_) => spark.emptyDataFrame
      }

      val outputLocation = s"${conf.outputLocation()}/experiment_id=$e/date=$date"

      import spark.implicits._
      getExperimentMetrics(e, experimentsSummary, errorAggregates, conf)
        .toDF()
        .drop(col("experiment_id"))
        .repartition(1)
        .write.mode("overwrite").parquet(outputLocation)

      logger.info(s"Wrote aggregates to $outputLocation")
      spark.stop()
    }
  }

  def getDate(conf: Conf): String =  {
   conf.date.get match {
      case Some(d) => d
      case _ => com.mozilla.telemetry.utils.yesterdayAsYYYYMMDD
    }   
  }

  def getExperiments(conf: Conf, data: DataFrame): List[String] = {
    conf.experiment.get match {
      case Some(e) => List(e)
      case _ => data // get all experiments
        .where(col("submission_date_s3") === getDate(conf))
        .select("experiment_id")
        .distinct()
        .collect()
        .toList
        .map(r => r(0).asInstanceOf[String])
    }
  }

  def getMetrics(conf: Conf, data: DataFrame) = {
    conf.metric.get match {
      case Some(m) => {
        List((m, (Histograms.definitions(includeOptin = true, nameJoiner = Histograms.prefixProcessJoiner _) ++ Scalars.definitions())(m.toUpperCase)))
      }
      case _ => {
        val histogramDefs = MainSummaryView.filterHistogramDefinitions(
          Histograms.definitions(includeOptin = true, nameJoiner = Histograms.prefixProcessJoiner _),
          useWhitelist = true)
        val scalarDefs = Scalars.definitions(includeOptin = true).toList
        scalarDefs ++ histogramDefs
      }
    }
  }

  def getExperimentMetrics(experiment: String, experimentsSummary: DataFrame, errorAggregates: DataFrame, conf: Conf): List[MetricAnalysis] = {
    val metadata = ExperimentAnalyzer.getExperimentMetadata(experimentsSummary).collect()
    val persisted = addPermutationsAndPersist(experimentsSummary, metadata, experiment)

    val metricList = getMetrics(conf, experimentsSummary)

    val metrics = metricList.flatMap {
      case (name: String, md: MetricDefinition) =>
        md match {
          case hd: HistogramDefinition =>
            new HistogramAnalyzer(name, hd, persisted).analyze()
          case sd: ScalarDefinition =>
            ScalarAnalyzer.getAnalyzer(name, sd, persisted).analyze()
          case _ => throw new UnsupportedOperationException("Unsupported metric definition type")
        }
    }

    val crashes = CrashAnalyzer.getExperimentCrashes(errorAggregates)
    (metadata ++ metrics ++ crashes).toList
  }

  // Adds a column for permutations, repartitions if warranted, and persists the result for quicker access
  def addPermutationsAndPersist(experimentsSummary: DataFrame,
                                metadata: Array[MetricAnalysis],
                                experiment: String): DataFrame = {
    val topLevelMetadata = metadata.filter(_.subgroup == MetricAnalyzer.topLevelLabel)
    val totalPings = topLevelMetadata.flatMap(_.statistics.get.filter(_.name == "Total Pings").map(_.value)).sum
    val branchCounts = topLevelMetadata.map {
      r => r.experiment_branch ->
        (r.statistics.get.collectFirst {case s: Statistic if s.name == "Total Clients" => s} match {
          case Some(s: Statistic) => s.value.toLong
          case _ => throw new Exception(s"Missing Total Clients metadata for branch ${r.experiment_branch}")
        })
    }.toMap

    val withPermutations = addPermutations(experimentsSummary.selectUsedColumns, branchCounts, experiment)
    (totalPings / rowsPerPartition match {
      case c if c > experimentsSummary.sparkSession.sparkContext.defaultParallelism =>
        withPermutations.repartition(c.toInt)
      case _ =>
        withPermutations
    }).persist(StorageLevel.MEMORY_AND_DISK)
  }

  def addPermutations(df: DataFrame, branchCounts: Map[String, Long], experiment: String): DataFrame = {
    // create the cutoffs from branchCounts
    val total = branchCounts.values.sum.toDouble
    val cutoffs = branchCounts
      .toList
      .sortBy(_._1) // sort by branch name
      .scanLeft(0L)(_ + _._2) // transform to cumulative branch counts
      .tail  // drop the initial 0 from scanLeft
      .map(_/total) // turn cumulative counts into cutoffs from 0 to 1

    val generator = weightedGenerator(cutoffs, experiment, numPermutations = 100) _
    val permutationsUDF = udf(generator)
    df.withColumn("permutations", permutationsUDF(col("client_id")))
  }
}
