/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.convert

import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.ServiceLoader

import com.google.common.collect.Maps
import com.google.common.io.Resources
import com.typesafe.config.Config
import org.apache.commons.csv.{CSVFormat, CSVParser}

trait EnrichmentCache {

  def get(args: Array[String]): Any
  def put(args: Array[String], value: Any): Unit
  def clear(): Unit

}

trait EnrichmentCacheFactory {
  def canProcess(conf: Config): Boolean
  def build(conf: Config): EnrichmentCache
}

object EnrichmentCache {
  def apply(conf: Config): EnrichmentCache = {
    import scala.collection.JavaConversions._
    val fac = ServiceLoader.load(classOf[EnrichmentCacheFactory]).find(_.canProcess(conf)).getOrElse(throw new RuntimeException("Could not find applicable EnrichmentCache"))
    fac.build(conf)
  }
}

// For testing purposes
class SimpleEnrichmentCache(val cache: java.util.Map[String, java.util.HashMap[String, AnyRef]] = Maps.newHashMap[String, java.util.HashMap[String, AnyRef]]()) extends EnrichmentCache {

  override def get(args: Array[String]): Any = Option(cache.get(args(0))).map(_.get(args(1))).getOrElse(null)


  override def put(args: Array[String], value: Any): Unit = {
    import scala.collection.JavaConversions._
    cache.getOrElseUpdate(args(0), Maps.newHashMap()).put(args(1), value.asInstanceOf[AnyRef])
  }

  override def clear(): Unit = cache.clear()
}

class SimpleEnrichmentCacheFactory extends EnrichmentCacheFactory {
  override def canProcess(conf: Config): Boolean = conf.hasPath("type") && conf.getString("type").equals("simple")

  override def build(conf: Config): EnrichmentCache = new SimpleEnrichmentCache(conf.getConfig("data").root().unwrapped().asInstanceOf[java.util.Map[String, java.util.HashMap[String, AnyRef]]])
}

class ResourceLoadingCache(path: String, idField: String, headers: Seq[String]) extends EnrichmentCache {
  import scala.collection.JavaConversions._
  private val file = Resources.asCharSource(Resources.getResource(path), StandardCharsets.UTF_8)
  private val csvReader = new CSVParser(file.openBufferedStream(), CSVFormat.DEFAULT.withHeader(headers: _*))
  private val data = csvReader.getRecords.map { rec =>
    rec.get(idField) -> rec.toMap
  }.toMap

  override def get(args: Array[String]): Any = data.get(args(0)).map(_.get(args(1))).orNull
  override def put(args: Array[String], value: Any): Unit = ???
  override def clear(): Unit = ???
}

class ResourceLoadingCacheFactory extends EnrichmentCacheFactory {
  override def canProcess(conf: Config): Boolean = conf.hasPath("type") && conf.getString("type").equals("resource")

  override def build(conf: Config): EnrichmentCache = {
    import scala.collection.JavaConversions._

    val path = conf.getString("path")
    val idField = conf.getString("id-field")
    val headers = conf.getStringList("columns")
    new ResourceLoadingCache(path, idField, headers.toList)
  }

}