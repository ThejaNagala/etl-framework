/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.abhioncbr.etlFramework.core

import com.abhioncbr.etlFramework.commons.Context
import com.abhioncbr.etlFramework.commons.ContextConstantEnum._
import com.abhioncbr.etlFramework.commons.ExecutionResult
import com.abhioncbr.etlFramework.commons.NotificationMessages.{extractNotSupported => ENS}
import com.abhioncbr.etlFramework.commons.extract.ExtractConf
import com.abhioncbr.etlFramework.commons.extract.ExtractionType
import com.abhioncbr.etlFramework.commons.job.JobStaticParamConf
import com.abhioncbr.etlFramework.commons.load.LoadConf
import com.abhioncbr.etlFramework.commons.load.LoadType
import com.abhioncbr.etlFramework.commons.transform.TransformConf
import com.abhioncbr.etlFramework.core.extractData.ExtractDataFromDB
import com.abhioncbr.etlFramework.core.extractData.ExtractDataFromFileSystem
import com.abhioncbr.etlFramework.core.extractData.ExtractDataFromHive
import com.abhioncbr.etlFramework.core.loadData.LoadDataIntoFileSystem
import com.abhioncbr.etlFramework.core.loadData.LoadDataIntoHive
import com.abhioncbr.etlFramework.core.transformData.Transform
import com.abhioncbr.etlFramework.core.transformData.TransformData
import com.abhioncbr.etlFramework.core.transformData.TransformUtil
import com.abhioncbr.etlFramework.jobConf.xml.ParseETLJobXml
import com.abhioncbr.etlFramework.metrics.stats.JobResult
import com.abhioncbr.etlFramework.metrics.stats.UpdateFeedStats
import com.typesafe.scalalogging.Logger
import org.apache.commons.lang.builder.ToStringBuilder
import org.apache.hadoop.conf.Configuration
import org.apache.spark.SparkContext
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.SQLContext
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import scala.xml.XML

class LaunchETLSparkJobExecution(jobName: String, startDate: Option[DateTime], endDate: Option[DateTime],
  configFilePath: String, otherParams: Option[Map[String, String]]) {
  private val logger = Logger(this.getClass)

  def configureETLJob: Either[Unit, String] = {
    // adding job params in context.
    Context.addContextualObject[Option[DateTime]](END_DATE, endDate)
    Context.addContextualObject[Option[DateTime]](START_DATE, startDate)
    Context.addContextualObject[Option[Map[String, String]]](OTHER_PARAM, otherParams)

    val appName = "ETL-" + jobName
    val sparkSession: SparkSession = SparkSession.builder().appName(appName).getOrCreate()

    // adding spark & hadoop configuration in context.
    Context.addContextualObject[SQLContext](SQL_CONTEXT, sparkSession.sqlContext)
    Context.addContextualObject[SparkContext](SPARK_CONTEXT, sparkSession.sparkContext)
    Context.addContextualObject[Configuration](HADOOP_CONF, sparkSession.sparkContext.hadoopConfiguration)

    // parsing configuration file & loading job params in context.
    val falseObject = false
    val parse = new ParseETLJobXml
    val output: Either[Unit, String] = parse.parseXml(configFilePath, falseObject) match {
      case Left(xmlContent) => parse.parseNode(XML.loadString(xmlContent)) match {
        case Left(tuple) =>
          Context.addContextualObject[JobStaticParamConf] (JOB_STATIC_PARAM_CONF, tuple._1)
          Context.addContextualObject[ExtractConf] (EXTRACT_CONF, tuple._2)
          Context.addContextualObject[TransformConf] (TRANSFORM_CONF, tuple._3)
          Context.addContextualObject[LoadConf] (LOAD_CONF, tuple._4)
          Left()
        case Right(parseError) => logger.error(parseError)
          Right(parseError)
      }
      case Right(xmlLoadError) => logger.error(xmlLoadError)
        Right(xmlLoadError)
    }
    output
  }

  def executeETLJob: Either[Array[JobResult], String] = {
    // First: extracting the data.
    val extractionResult: Either[Array[ExecutionResult], String] = extract
    if (extractionResult.isRight) {
      Right(extractionResult.right.get)
    } else {
      // TODO/FIXME: validate extracted data based on condition & boolean operator.
      // TODO/FIXME: handle scenario of extracted dataframes with no data.
      logger.info("Extraction phase of the feed is completed")

      // Second: transforming the extracted data.
      val transformedResult = transform(extractionResult.left.get)
      if (transformedResult.isRight) {
        Right(transformedResult.right.get)
      } else {
        // TODO/FIXME: validate transformed data based on condition & boolean operator.
        // TODO/FIXME: handle scenario of transformed dataframes with no data.
        logger.info("Transformation phase of the feed is completed")

        // Third: loading the transformed data.
        val loadResult = load(transformedResult.left.get)
        loadResult
      }
    }
  }

  private def extract: Either[Array[ExecutionResult], String] = {
    val extract: ExtractConf = Context.getContextualObject[ExtractConf](EXTRACT_CONF)
    val extractedFeedsOutput: Array[Either[ExecutionResult, String]] = extract.feeds.map(feed => feed.extractionType match {
      case ExtractionType.FILE_SYSTEM => new ExtractDataFromFileSystem(feed).getRawData

      case ExtractionType.JDBC => new ExtractDataFromDB(feed).getRawData

      case ExtractionType.HIVE => new ExtractDataFromHive(feed).getRawData

      case ExtractionType.UNSUPPORTED => Right(ENS(ExtractionType.getDataValue(feed.extractionType)))
    })

    // checking whether exception occurred in feed extraction.
    val filteredRight: Array[String] = extractedFeedsOutput.filter(_.isRight).map(_.right.get)
    if (filteredRight.length > 0) { Right(filteredRight.mkString(" , ")) }
    else { Left(extractedFeedsOutput.map(_.left.get)) }
  }

  private def transform(extractionDF: Array[ExecutionResult]): Either[Array[ExecutionResult], String] = {
    val transform: Transform = TransformUtil.prepareTransformation(Context.getContextualObject[TransformConf](TRANSFORM_CONF))
    new TransformData(transform).performTransformation(extractionDF)
  }

  /* private def validate(transformationDF: Array[TransformResult]): Either[Array[(DataFrame, DataFrame, Any, Any)], String] = {
    // testing whether transformed data frames have data or not.
    transformationDF.map(res => res.resultDF).foreach(df => if (df.first == null) {
      return Right("Transformed data frame contains no data row")
    })

    var output: Array[(DataFrame, DataFrame, Any, Any)] = Array()
    transformationDF.foreach(arrayElement => {
      val validator = new ValidateTransformedData
      val validateSchemaResult = validator.validateSchema (arrayElement.resultDF)
      if(validateSchemaResult._1) {
        output = output ++ validator.validateData(arrayElement.resultDF, validateSchemaResult._2.get, arrayElement
          .otherAttributes.get("validCount"), arrayElement.otherAttributes.get("invalidCount"))
      } else {
        validateSchemaResult._2 match {
          case Some(_2) => logger.error("hive table schema & data frame schema does not match. Below are schemas for reference -")
            logger.error(s"table schema:: ${_2.mkString}")
            logger.error(s"data frame schema:: ${validateSchemaResult._3.get.mkString}")
          case None => logger.error("provided hive table does not exist.")
          }
        return Right("Validation failed. Please check the log.")
      }
    })
    Left(output)
  } */

  private def load(validationArrayDF: Array[ExecutionResult]): Either[Array[JobResult], String] = {
    val falseObject = false
    // TODO/FIXME: load tables for multiple data frames
    val loadResult: Array[JobResult] = validationArrayDF.map(validate => {
      val loadConf = Context.getContextualObject[LoadConf](LOAD_CONF)

      // TODO/FIXME: handle mapping of the transform feeds with load feeds.
      val feed = loadConf.feeds.head
      val loadType = feed.loadType

      val loadResult: Either[Boolean, String] = loadType match {
        case LoadType.HIVE => new LoadDataIntoHive(feed).loadTransformedData(validate.resultDF)
        case LoadType.JDBC => Right(s"loading data to $loadType is not supported right now.")
        case LoadType.FILE_SYSTEM => new LoadDataIntoFileSystem(feed).loadTransformedData(validate.resultDF)
        case _ => Right(s"loading data to $loadType is not supported right now.")
      }
      // writing output data tuple.
      (loadResult, validate)
    }).map(result => {
      if (result._1.isRight) {
        JobResult(falseObject, result._2.feedName, 0, 0, 0, 0, result._1.right.get)
      } else {
        JobResult(result._1.left.get, result._2.feedName, result._2.resultDF.count, 0, 0, 0, "NONE")
      }
    })

    Left(loadResult)
  }
}

object LaunchETLSparkJobExecution extends App{
  private val logger = Logger(this.getClass)

  case class CommandOptions(jobName: String = "", configFilePath: String = "", startDate: Option[DateTime] = None,
    endDate: Option[DateTime] = None, otherParams: Option[Map[String, String]] = None) {

    override def toString: String = ToStringBuilder.reflectionToString(this)
  }

  def launch(args: Array[String]): Unit = {
    val datePattern = "yyyy-MM-dd HH:mm:ss"
    val dateParser = DateTimeFormat.forPattern(datePattern)

    val parser = new scopt.OptionParser[CommandOptions](programName = "ETL") {
      opt[String]('j', name = "jobName")
        .action((e, c) => c.copy(jobName = e))
        .text("[Required Param]: Etl Job Name Is Required For All ETL Jobs.")
        .required

      opt[String]('c', "configFilePath")
        .action((cfp, c) => c.copy(configFilePath = cfp))
        .text("[Required Param]: Config File Is Required For All ETL Jobs.")
        .required

      opt[String]('s', name = "startDate")
        .action((sd, c) => c.copy(startDate = if (!sd.trim.isEmpty){ Some(dateParser.parseDateTime(sd)) } else { None }))
        .text("[Optional Param]: Needed For All ETL Jobs Except For Hourly & Once Frequency One's.")
        .optional

      opt[String]('e', name = "endDate")
        .action((ed, c) => c.copy(endDate = if (!ed.trim.isEmpty) { Some(dateParser.parseDateTime(ed)) } else { None }))
        .text("[Optional Param]: Needed For All Date Range ETL Jobs.")
        .optional

      opt[String]('o', name = "other_params")
        .action((op, c) => c.copy(otherParams = Some(op.dropRight(1).drop(1).split(',').map(str => {
          val temp = str.split('=')
          (temp(0), temp(1))
        }).toMap)))
        .text("[Optional Param]: Can Be Used For Specifying Extra Params. Should Be Of key-Value Pattern Like '[a=b,x=y]'")
        .optional

    }

    val exitCode: Int = parser.parse(args, CommandOptions()) match {
      case Some(opts) =>
        logger.info(s"Going to start the execution of the etl feed job: ${opts.toString}")
        execute(opts)
      case None => parser.showTryHelp()
        -1
    }
    logger.info(s"Etl job finish with exit code: $exitCode")
    System.exit(exitCode)
  }

  def execute(opts: CommandOptions): Int = {
    val etlExecutor = new LaunchETLSparkJobExecution(opts.jobName, opts.startDate,
      opts.endDate, opts.configFilePath, opts.otherParams)

    val updateFeedStats: UpdateFeedStats = new UpdateFeedStats(opts.jobName, opts.startDate.getOrElse(DateTime.now))
    val exitCode: Int = etlExecutor.configureETLJob match {
      case Left(u: Unit) =>
        logger.info(s"ETL job Config file validated $u")
        val start = System.currentTimeMillis
        // executing ETL job
        val feedJobOutput = etlExecutor.executeETLJob
        val end = System.currentTimeMillis

        feedJobOutput match {
          case Left(dataArray) =>
            // TODO/FIXME: Prometheus push metrics, commented in refactoring [will be triggered based on job static param]
            // pushMetrics(opts.feedName)
            val updateStatusData: Array[String] = dataArray
              .map(data => updateFeedStats.updateFeedStatInFile(end - start, data))
              .filter(_.isRight).map(_.right.get)
            if (updateStatusData.length > 0) { logger.error(updateStatusData.mkString(" , "))
              -1
            } else { 0 }
          case Right(str) => updateFeedStats.updateFeedStatInFile(end - start, getDefaultJobResult(opts.jobName, str))
            logger.error(str)
            -1
        }
      case Right(str) =>
        updateFeedStats.updateFeedStatInFile(executionTime = 0, getDefaultJobResult(opts.jobName, str))
        logger.error(str)
        -1
    }
    exitCode
  }

  def getDefaultJobResult(etlJobName: String, failureReason: String): JobResult = {
    val falseObject: Boolean = false
    JobResult(falseObject, etlJobName, 0, 0, 0, 0, failureReason)
  }

  launch(args)
}
