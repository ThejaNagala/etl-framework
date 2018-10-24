package com.abhioncbr.etlFramework.jobConf.xml

import com.abhioncbr.etlFramework.commons.common.file.{FileNameParam, FilePath, PathInfixParam}
import com.typesafe.scalalogging.Logger

object ParseDataPath {
  private val logger = Logger(this.getClass)

  def fromXML(node: scala.xml.NodeSeq): Option[FilePath] = {
    val pathNode: scala.xml.NodeSeq =  node \ "path"
    val pathPatternNode: scala.xml.NodeSeq =  node \ "pathPattern"
    if(pathNode.nonEmpty) {
      val filePathObject: Option[FilePath] = ParseUtil.parseFilePathString(pathNode.text) match {
        case Left(output) => Some(output)
        case Right(message) => logger.warn(s"[ParseDataPath: fromXML: ] - $message"); None
      }
      filePathObject
    } else if(pathPatternNode.nonEmpty) Some(parsePathPattern(pathPatternNode))
    else None
  }

  def parsePathPattern(node: scala.xml.NodeSeq): FilePath = {
    val dataPath: FilePath = FilePath(pathPrefix = Some((node \ "initialPath").text),
      groupPatterns = Some(Array[PathInfixParam]((node \ "groupPattern" \ "member").toList map { s => ParseGroupPatterns.fromXML(s) }: _*)),
      feedPattern = Some(ParseFeedPattern.fromXML(node \ "feedPattern")),
      fileName = Some(ParseFileName.fromXML(node \ "fileName"))
    )
    dataPath
  }
}

object ParseGroupPatterns {
  def fromXML(node: scala.xml.NodeSeq): PathInfixParam = {
    val pathInfixParam : PathInfixParam = PathInfixParam(order = Some(ParseUtil.parseInt((node \ "order").text)),
      infixPattern= (node \ "groupNamePattern").text,
      formatInfix = Some(ParseUtil.parseBoolean((node \ "formatGroupName").text)),
      formatInfixArgs = Some(ParseGeneralParams.fromXML(node, nodeTag= "formatArgValues")))
    pathInfixParam
  }
}

object ParseFeedPattern {
  def fromXML(node: scala.xml.NodeSeq): PathInfixParam = {
    val pathInfixParam : PathInfixParam = PathInfixParam( infixPattern= (node \ "feedNamePattern").text,
      formatInfix = Some(ParseUtil.parseBoolean((node \ "formatFeedName").text)),
      formatInfixArgs = Some(ParseGeneralParams.fromXML(node, nodeTag= "formatArgValues")))
    pathInfixParam
  }
}

object ParseFileName {
  def fromXML(node: scala.xml.NodeSeq): FileNameParam = {
    val fileName: FileNameParam = FileNameParam(fileNamePrefix = ParseUtil.parseNodeText(node \ "prefix"),
      fileNameSuffix = ParseUtil.parseNodeText(node \ "suffix"), fileNameSeparator = ParseUtil.parseNodeText(node \ "separator", Some(".")))
      fileName
   }
}
