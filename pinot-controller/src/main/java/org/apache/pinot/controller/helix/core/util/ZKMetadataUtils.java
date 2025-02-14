/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.controller.helix.core.util;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.pinot.common.metadata.segment.SegmentPartitionMetadata;
import org.apache.pinot.common.metadata.segment.SegmentZKMetadata;
import org.apache.pinot.segment.spi.SegmentMetadata;
import org.apache.pinot.segment.spi.partition.PartitionFunction;
import org.apache.pinot.segment.spi.partition.metadata.ColumnPartitionMetadata;
import org.apache.pinot.spi.utils.CommonConstants;
import org.apache.pinot.spi.utils.builder.TableNameBuilder;


public class ZKMetadataUtils {
  private ZKMetadataUtils() {
  }

  /**
   * Creates the segment ZK metadata for a new segment.
   */
  public static SegmentZKMetadata createSegmentZKMetadata(String tableNameWithType, SegmentMetadata segmentMetadata,
      String downloadUrl, @Nullable String crypterName, long segmentSizeInBytes) {
    SegmentZKMetadata segmentZKMetadata = new SegmentZKMetadata(segmentMetadata.getName());
    updateSegmentZKMetadata(tableNameWithType, segmentZKMetadata, segmentMetadata, downloadUrl, crypterName,
        segmentSizeInBytes);
    segmentZKMetadata.setPushTime(System.currentTimeMillis());
    return segmentZKMetadata;
  }

  /**
   * Refreshes the segment ZK metadata for a segment being replaced.
   */
  public static void refreshSegmentZKMetadata(String tableNameWithType, SegmentZKMetadata segmentZKMetadata,
      SegmentMetadata segmentMetadata, String downloadUrl, @Nullable String crypterName, long segmentSizeInBytes) {
    updateSegmentZKMetadata(tableNameWithType, segmentZKMetadata, segmentMetadata, downloadUrl, crypterName,
        segmentSizeInBytes);
    segmentZKMetadata.setRefreshTime(System.currentTimeMillis());
  }

  private static void updateSegmentZKMetadata(String tableNameWithType, SegmentZKMetadata segmentZKMetadata,
      SegmentMetadata segmentMetadata, String downloadUrl, @Nullable String crypterName, long segmentSizeInBytes) {
    if (segmentMetadata.getTimeInterval() != null) {
      segmentZKMetadata.setStartTime(segmentMetadata.getStartTime());
      segmentZKMetadata.setEndTime(segmentMetadata.getEndTime());
      segmentZKMetadata.setTimeUnit(segmentMetadata.getTimeUnit());
    } else {
      segmentZKMetadata.setStartTime(-1);
      segmentZKMetadata.setEndTime(-1);
      segmentZKMetadata.setTimeUnit(null);
    }
    if (segmentMetadata.getVersion() != null) {
      segmentZKMetadata.setIndexVersion(segmentMetadata.getVersion().name());
    } else {
      segmentZKMetadata.setIndexVersion(null);
    }
    segmentZKMetadata.setTotalDocs(segmentMetadata.getTotalDocs());
    segmentZKMetadata.setSizeInBytes(segmentSizeInBytes);
    segmentZKMetadata.setCrc(Long.parseLong(segmentMetadata.getCrc()));
    segmentZKMetadata.setCreationTime(segmentMetadata.getIndexCreationTime());
    segmentZKMetadata.setDownloadUrl(downloadUrl);
    segmentZKMetadata.setCrypterName(crypterName);

    // Set partition metadata
    Map<String, ColumnPartitionMetadata> columnPartitionMap = new HashMap<>();
    segmentMetadata.getColumnMetadataMap().forEach((column, columnMetadata) -> {
      PartitionFunction partitionFunction = columnMetadata.getPartitionFunction();
      if (partitionFunction != null) {
        ColumnPartitionMetadata columnPartitionMetadata =
            new ColumnPartitionMetadata(partitionFunction.getName(), partitionFunction.getNumPartitions(),
                columnMetadata.getPartitions(), partitionFunction.getFunctionConfig());
        columnPartitionMap.put(column, columnPartitionMetadata);
      }
    });
    if (!columnPartitionMap.isEmpty()) {
      segmentZKMetadata.setPartitionMetadata(new SegmentPartitionMetadata(columnPartitionMap));
    } else {
      segmentZKMetadata.setPartitionMetadata(null);
    }

    // Update custom metadata
    // NOTE: Do not remove existing keys because they can be set by the HTTP header from the segment upload request
    Map<String, String> customMap = segmentZKMetadata.getCustomMap();
    if (customMap == null) {
      customMap = segmentMetadata.getCustomMap();
    } else {
      customMap.putAll(segmentMetadata.getCustomMap());
    }
    segmentZKMetadata.setCustomMap(customMap);

    // Set fields specific to realtime table
    if (TableNameBuilder.isRealtimeTableResource(tableNameWithType)) {
      segmentZKMetadata.setStatus(CommonConstants.Segment.Realtime.Status.UPLOADED);

      // NOTE:
      // - If start/end offset is available in the uploaded segment, update them in the segment ZK metadata
      // - If not, keep the existing start/end offset in the segment ZK metadata unchanged
      if (segmentMetadata.getStartOffset() != null) {
        segmentZKMetadata.setStartOffset(segmentMetadata.getStartOffset());
      }
      if (segmentMetadata.getEndOffset() != null) {
        segmentZKMetadata.setEndOffset(segmentMetadata.getEndOffset());
      }
    }
  }
}
