/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.orc;

import org.apache.flink.api.common.serialization.BulkWriter;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.connector.file.src.FileSourceSplit;
import org.apache.flink.connector.file.src.reader.BulkFormat;
import org.apache.flink.orc.shim.OrcShim;
import org.apache.flink.orc.vector.RowDataVectorizer;
import org.apache.flink.orc.writer.OrcBulkWriterFactory;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.Projection;
import org.apache.flink.table.connector.format.BulkDecodingFormat;
import org.apache.flink.table.connector.format.EncodingFormat;
import org.apache.flink.table.connector.format.ProjectableDecodingFormat;
import org.apache.flink.table.connector.sink.DynamicTableSink;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.vector.VectorizedColumnBatch;
import org.apache.flink.table.expressions.Expression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.factories.BulkReaderFormatFactory;
import org.apache.flink.table.factories.BulkWriterFormatFactory;
import org.apache.flink.table.factories.DynamicTableFactory;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.RowType;

import org.apache.hadoop.conf.Configuration;
import org.apache.orc.TypeDescription;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Orc format factory for file system. */
public class OrcFileFormatFactory implements BulkReaderFormatFactory, BulkWriterFormatFactory {

    public static final String IDENTIFIER = "orc";

    @Override
    public String factoryIdentifier() {
        return IDENTIFIER;
    }

    @Override
    public Set<ConfigOption<?>> requiredOptions() {
        return new HashSet<>();
    }

    @Override
    public Set<ConfigOption<?>> optionalOptions() {
        // support "orc.*"
        return new HashSet<>();
    }

    private static Properties getOrcProperties(ReadableConfig options) {
        Properties orcProperties = new Properties();
        Properties properties = new Properties();
        ((org.apache.flink.configuration.Configuration) options).addAllToProperties(properties);
        properties.forEach((k, v) -> orcProperties.put(IDENTIFIER + "." + k, v));
        return orcProperties;
    }

    @Override
    public BulkDecodingFormat<RowData> createDecodingFormat(
            DynamicTableFactory.Context context, ReadableConfig formatOptions) {
        return new OrcBulkDecodingFormat(formatOptions);
    }

    @Override
    public EncodingFormat<BulkWriter.Factory<RowData>> createEncodingFormat(
            DynamicTableFactory.Context context, ReadableConfig formatOptions) {
        return new EncodingFormat<BulkWriter.Factory<RowData>>() {
            @Override
            public BulkWriter.Factory<RowData> createRuntimeEncoder(
                    DynamicTableSink.Context sinkContext, DataType consumedDataType) {
                RowType formatRowType = (RowType) consumedDataType.getLogicalType();
                LogicalType[] orcTypes = formatRowType.getChildren().toArray(new LogicalType[0]);

                TypeDescription typeDescription =
                        OrcSplitReaderUtil.logicalTypeToOrcType(formatRowType);

                return new OrcBulkWriterFactory<>(
                        new RowDataVectorizer(typeDescription.toString(), orcTypes),
                        getOrcProperties(formatOptions),
                        new Configuration());
            }

            @Override
            public ChangelogMode getChangelogMode() {
                return ChangelogMode.insertOnly();
            }
        };
    }

    private static class OrcBulkDecodingFormat
            implements BulkDecodingFormat<RowData>,
                    ProjectableDecodingFormat<BulkFormat<RowData, FileSourceSplit>> {

        private final ReadableConfig formatOptions;
        private List<ResolvedExpression> filters;

        public OrcBulkDecodingFormat(ReadableConfig formatOptions) {
            this.formatOptions = formatOptions;
        }

        @Override
        public BulkFormat<RowData, FileSourceSplit> createRuntimeDecoder(
                DynamicTableSource.Context sourceContext,
                DataType producedDataType,
                int[][] projections) {
            List<OrcFilters.Predicate> orcPredicates = new ArrayList<>();

            if (filters != null) {
                for (Expression pred : filters) {
                    OrcFilters.Predicate orcPred = OrcFilters.toOrcPredicate(pred);
                    if (orcPred != null) {
                        orcPredicates.add(orcPred);
                    }
                }
            }

            Properties properties = getOrcProperties(formatOptions);
            Configuration conf = new Configuration();
            properties.forEach((k, v) -> conf.set(k.toString(), v.toString()));

            return OrcColumnarRowInputFormat.createPartitionedFormat(
                    OrcShim.defaultShim(),
                    conf,
                    (RowType) producedDataType.getLogicalType(),
                    Collections.emptyList(),
                    null,
                    Projection.of(projections).toTopLevelIndexes(),
                    orcPredicates,
                    VectorizedColumnBatch.DEFAULT_SIZE);
        }

        @Override
        public ChangelogMode getChangelogMode() {
            return ChangelogMode.insertOnly();
        }

        @Override
        public void applyFilters(List<ResolvedExpression> filters) {
            this.filters = filters;
        }
    }
}
