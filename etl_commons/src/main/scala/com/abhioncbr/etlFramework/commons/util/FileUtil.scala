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

package com.abhioncbr.etlFramework.commons.util

import com.abhioncbr.etlFramework.commons.Context
import com.abhioncbr.etlFramework.commons.ContextConstantEnum.HADOOP_CONF
import com.abhioncbr.etlFramework.commons.ContextConstantEnum.JOB_STATIC_PARAM_CONF
import com.abhioncbr.etlFramework.commons.ContextConstantEnum.OTHER_PARAM
import com.abhioncbr.etlFramework.commons.NotificationMessages
import com.abhioncbr.etlFramework.commons.ProcessFrequencyEnum
import com.abhioncbr.etlFramework.commons.common.DataPath
import com.abhioncbr.etlFramework.commons.common.FileNameParam
import com.abhioncbr.etlFramework.commons.common.GeneralParamConf
import com.abhioncbr.etlFramework.commons.common.PathInfixParam
import com.abhioncbr.etlFramework.commons.job.JobStaticParamConf
import java.text.DecimalFormat
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.Path
import org.joda.time.DateTime
import org.joda.time.Days
import org.joda.time.DurationFieldType

object FileUtil {
  def getFilePathString(filePath: DataPath): String = {
    val pathPrefixString: String = appendDirSeparator(filePath.pathPrefix.getOrElse(""))
    val catalogueString: String = appendDirSeparator(getInfixPathString[Array[PathInfixParam]](filePath.cataloguePatterns))
    val feedString: String = appendDirSeparator(getInfixPathString[PathInfixParam](filePath.feedPattern))
    val fileNameString: String = getFileNameString(filePath.fileName)
    s"""$pathPrefixString$catalogueString$feedString$fileNameString"""
  }

  def getFilePathObject(filePathString: String, fileNameSeparator: String = "."): Either[DataPath, String] = {
    val conf = Context.getContextualObject[Configuration](HADOOP_CONF)

    try {
      val path: Path = new Path(filePathString)
      val fileSystem: FileSystem = path.getFileSystem(conf)
      if (fileSystem.exists(path)) {
        val pathPrefix: String = path.getParent.toString

        val filePath: DataPath =
          if (!path.getName.contains(fileNameSeparator)) {
            DataPath(Some(pathPrefix), feedPattern = Some(PathInfixParam(infixPattern = path.getName))) }
          else {
            DataPath(Some(pathPrefix), fileName = Some(parseFileName(path.getName, fileNameSeparator))) }

        Left(filePath)
      } else { Right(NotificationMessages.fileDoesNotExist(filePathString)) }
    } catch {
      case ex: Exception => Right(ex.getMessage)
    }
  }

  private def appendDirSeparator(subPath: String): String = {
    if (subPath.isEmpty || subPath.toCharArray.last == '/'){subPath}
    else { s"$subPath/" }
  }

  private def parseFileName(rawFileName: String, fileNameSeparator: String = "."): FileNameParam = {
    val nameParts: List[String] = rawFileName.split(s"[$fileNameSeparator]").toList
    FileNameParam(nameParts.lift(0), nameParts.lift(1), Some(fileNameSeparator))
  }

  private def getFormattedString(pattern: String, args: Option[Array[String]]): String =
    String.format(pattern, args.get: _*)

  private def getFileNameString(fileNameParamOption: Option[FileNameParam]): String = {
    fileNameParamOption.getOrElse(None) match {
      case fileNameParam: FileNameParam =>
        s"""${fileNameParam.fileNamePrefix.getOrElse("*")}${fileNameParam.fileNameSeparator.get}${fileNameParam.fileNameSuffix
          .getOrElse("*")}"""
      case None => ""
    }

  }

  private def getInfixPathString[T](infixObject: Option[T]): String = {
    val output = infixObject.getOrElse(None) match {
      case catalogue: Array[PathInfixParam] => catalogue.map(gr =>
        if (gr.formatInfix.get) { getFormattedString(gr.infixPattern, mapFormatArgs(gr.formatInfixArgs)) }
          else { gr.infixPattern }).mkString("/")
      case feed: PathInfixParam =>
        if (feed.formatInfix.get) { getFormattedString(feed.infixPattern, mapFormatArgs(feed.formatInfixArgs)) }
        else { feed.infixPattern }
      case None => ""
    }
    output
  }

  def mapFormatArgs(generalParams: Option[Array[GeneralParamConf]]): Option[Array[String]] = {
    val programParams = Context.getContextualObject[Option[Map[String, String]]](OTHER_PARAM).get
    generalParams.getOrElse(None) match {
      case None => None
      case params: Array[GeneralParamConf] =>
        Some(params.map(param => programParams.getOrElse(param.paramName, param.paramValue)))
    }
  }

  def getProcessFrequencyPattern(firstDate: Option[DateTime], secondDate: Option[DateTime]): String = {
    val processFrequency: ProcessFrequencyEnum.frequencyType = Context
      .getContextualObject[JobStaticParamConf](JOB_STATIC_PARAM_CONF)
      .processFrequency
    import com.abhioncbr.etlFramework.commons.ProcessFrequencyEnum._
    processFrequency match {
      case ONCE => ""

      case HOURLY =>
        val parsedDate = processDate(firstDate.get)
        s"""${parsedDate._5}/${parsedDate._4}/${parsedDate._2}/${parsedDate._1}"""

      case DAILY =>
        val parsedDate = processDate(firstDate.get)
        s"""${parsedDate._5}/${parsedDate._4}/${parsedDate._2}/*"""

      case MONTHLY =>
        val parsedDate = processDate(firstDate.get)
        s"""${parsedDate._5}/${parsedDate._4}/*/*"""

      case YEARLY =>
        val parsedDate = processDate(firstDate.get)
        s"""${parsedDate._5}/*/*/*"""

      case WEEKLY =>
        val parsedDate = processDate(firstDate.get)
        val dateString = multipleDatesPattern(parsedDate._3.roundFloorCopy(), parsedDate._3.roundCeilingCopy)
        s"""${parsedDate._5}/${parsedDate._4}/$dateString/*"""

      case DATE_RANGE =>
        val parsedDate = processDate(firstDate.get)
        val dateString = multipleDatesPattern(firstDate.get, secondDate.get)
        s"""${parsedDate._5}/${parsedDate._4}/$dateString/*"""
    }
  }

  private def multipleDatesPattern(firstDate: DateTime, secondDate: DateTime): String = {
    val df = new DecimalFormat("00")
    val days = Days.daysBetween(firstDate, secondDate).getDays
    val dateArray = new Array[DateTime](days)
    for (i <- 0 until days) {
      dateArray(i) = firstDate.withFieldAdded(DurationFieldType.days(), i)
    }
    val dateValues =
      dateArray.map(date => (date, df.format(date.getDayOfMonth))).toMap.values
    val dateString = if (dateValues.size > 1) {
      dateValues.mkString("{", ",", "}")
    } else { dateValues.mkString }
    dateString
  }

  private def processDate(date: DateTime): (String, String, DateTime.Property, String, Int) = {
    val df = new DecimalFormat("00")
    val hour: String = df.format(date.getHourOfDay)
    val day: String = df.format(date.getDayOfMonth)
    val weekOfWeekYear: DateTime.Property = date.weekOfWeekyear
    val month: String = df.format(date.getMonthOfYear)
    val year: Int = date.getYear
    (hour, day, weekOfWeekYear, month, year)
  }
}
