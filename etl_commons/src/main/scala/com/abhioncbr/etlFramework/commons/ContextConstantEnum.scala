package com.abhioncbr.etlFramework.commons

object ContextConstantEnum extends Enumeration{
  type constant = Value
  val FIRST_DATE, SECOND_DATE,
  HADOOP_CONF, SPARK_CONTEXT, SQL_CONTEXT, HIVE_CONTEXT,
  JOB_STATIC_PARAM, EXTRACT, TRANSFORM, LOAD, SCHEMA = Value
}
