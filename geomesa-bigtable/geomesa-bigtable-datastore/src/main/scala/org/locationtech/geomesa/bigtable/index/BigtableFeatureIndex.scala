/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.bigtable.index

import com.google.cloud.bigtable.hbase.BigtableExtendedScan
import com.google.common.collect.Lists
import com.typesafe.scalalogging.LazyLogging
import org.apache.hadoop.hbase.TableName
import org.apache.hadoop.hbase.client._
import org.apache.hadoop.hbase.filter.MultiRowRangeFilter.RowRange
import org.apache.hadoop.hbase.filter.{MultiRowRangeFilter, Filter => HFilter}
import org.locationtech.geomesa.hbase.coprocessor.utils.CoprocessorConfig
import org.locationtech.geomesa.hbase.data.{HBaseDataStore, HBaseQueryPlan, ScanPlan}
import org.locationtech.geomesa.hbase.index._
import org.locationtech.geomesa.hbase.index.legacy._
import org.locationtech.geomesa.hbase.{HBaseFilterStrategyType, HBaseIndexManagerType}
import org.locationtech.geomesa.index.index.IndexAdapter
import org.locationtech.geomesa.utils.index.IndexMode.IndexMode
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}


object BigtableFeatureIndex extends HBaseIndexManagerType {

  // note: keep in priority order for running full table scans
  override val AllIndices: Seq[HBaseFeatureIndex] =
    Seq(BigtableZ3Index, BigtableZ3IndexV1, BigtableXZ3Index, BigtableZ2Index, BigtableZ2IndexV1,
      BigtableXZ2Index, BigtableIdIndex, BigtableAttributeIndex, BigtableAttributeIndexV3, BigtableAttributeIndexV2,
      BigtableAttributeIndexV1)

  override val CurrentIndices: Seq[HBaseFeatureIndex] =
    Seq(BigtableZ3Index, BigtableXZ3Index, BigtableZ2Index, BigtableXZ2Index, BigtableIdIndex, BigtableAttributeIndex)

  override def indices(sft: SimpleFeatureType, mode: IndexMode): Seq[HBaseFeatureIndex] =
    super.indices(sft, mode).asInstanceOf[Seq[HBaseFeatureIndex]]

  override def index(identifier: String): HBaseFeatureIndex = super.index(identifier).asInstanceOf[HBaseFeatureIndex]
}

trait BigtablePlatform extends HBasePlatform with LazyLogging {

  override def buildPlatformScanPlan(ds: HBaseDataStore,
                                     sft: SimpleFeatureType,
                                     filter: HBaseFilterStrategyType,
                                     ranges: Seq[Query],
                                     table: TableName,
                                     hbaseFilters: Seq[(Int, HFilter)],
                                     coprocessor: Option[CoprocessorConfig],
                                     toFeatures: (Iterator[Result]) => Iterator[SimpleFeature]): HBaseQueryPlan = {
    if (hbaseFilters.nonEmpty) {
      // bigtable does support some filters, but currently we only use custom filters that aren't supported
      throw new IllegalArgumentException(s"Bigtable doesn't support filters: ${hbaseFilters.mkString(", ")}")
    }

    // check if these Scans or Gets
    // Only in the case of 'ID IN ()' queries will this be Gets
    val scans = ranges.head match {
      case t: Get  => configureGet(ranges)
      case t: Scan => configureBigtableExtendedScan(ds, ranges)
    }

    ScanPlan(filter, table, scans, toFeatures)
  }

  private def configureGet(originalRanges: Seq[Query]): Seq[Scan] = {
    // convert Gets to Scans for Spark SQL compatibility
    originalRanges.map { r =>
      val g = r.asInstanceOf[Get]
      val start = g.getRow
      val end = IndexAdapter.rowFollowingRow(start)
      new Scan(g).setStartRow(start).setStopRow(end).setSmall(true)
    }
  }

  private def configureBigtableExtendedScan(ds: HBaseDataStore, originalRanges: Seq[Query]): Seq[Scan] = {
    import scala.collection.JavaConversions._
    val rowRanges = Lists.newArrayList[RowRange]()
    originalRanges.foreach { r =>
      rowRanges.add(new RowRange(r.asInstanceOf[Scan].getStartRow, true, r.asInstanceOf[Scan].getStopRow, false))
    }
    val sortedRowRanges = MultiRowRangeFilter.sortAndMerge(rowRanges)
    val numRanges = sortedRowRanges.size()
    val numThreads = ds.config.queryThreads
    // TODO GEOMESA-1802 parameterize this?
    val rangesPerThread = math.min(ds.config.maxRangesPerExtendedScan, math.max(1,math.ceil(numRanges/numThreads*2).toInt))
    // TODO GEOMESA-1802 align partitions with region boundaries
    val groupedRanges = Lists.partition(sortedRowRanges, rangesPerThread)

    // group scans into batches to achieve some client side parallelism
    val groupedScans = groupedRanges.map { localRanges =>
      // TODO GEOMESA-1802
      // currently, this constructor will call sortAndMerge a second time
      // this is unnecessary as we have already sorted and merged above
      val scan = new BigtableExtendedScan

      localRanges.foreach { r => scan.addRange(r.getStartRow, r.getStopRow) }
      scan
    }

    groupedScans
  }
}

case object BigtableZ2Index extends HBaseLikeZ2Index with BigtablePlatform
case object BigtableZ2IndexV1 extends HBaseLikeZ2IndexV1 with BigtablePlatform
case object BigtableZ3Index extends HBaseLikeZ3Index with BigtablePlatform
case object BigtableZ3IndexV1 extends HBaseLikeZ3IndexV1 with BigtablePlatform
case object BigtableIdIndex extends HBaseIdLikeIndex with BigtablePlatform
case object BigtableXZ2Index extends HBaseLikeXZ2Index with BigtablePlatform
case object BigtableXZ3Index extends HBaseLikeXZ3Index with BigtablePlatform

case object BigtableAttributeIndex extends HBaseLikeAttributeIndex with BigtablePlatform
case object BigtableAttributeIndexV3 extends HBaseLikeAttributeIndexV3 with BigtablePlatform
case object BigtableAttributeIndexV2 extends HBaseLikeAttributeIndexV2 with BigtablePlatform
case object BigtableAttributeIndexV1 extends HBaseLikeAttributeIndexV1 with BigtablePlatform
