/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.core;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Locale;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.lucene912.Lucene912Codec;
import org.apache.lucene.codecs.lucene912.Lucene912Codec.Mode;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.schema.DenseVectorField;
import org.apache.solr.schema.FieldType;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.util.plugin.SolrCoreAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-field CodecFactory implementation, extends Lucene's and returns postings format
 * implementations according to the schema configuration. <br>
 * Also, a string argument with name <code>compressionMode</code> can be provided to chose between
 * the different compression options for stored fields
 *
 * @lucene.experimental
 */
public class SchemaCodecFactory extends CodecFactory implements SolrCoreAware {

  /** Key to use in init arguments to set the compression mode in the codec. */
  public static final String COMPRESSION_MODE = "compressionMode";

  public static final Mode SOLR_DEFAULT_COMPRESSION_MODE = Mode.BEST_SPEED;

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private Codec codec;
  private volatile SolrCore core;

  // TODO: we need to change how solr does this?
  // rather than a string like "Direct" you need to be able to pass parameters
  // and everything to a field in the schema, e.g. we should provide factories for
  // the Lucene's core formats (Memory, Direct, ...) and such.
  //
  // So I think a FieldType should return PostingsFormat, not a String.
  // how it constructs this from the XML... i don't care.

  @Override
  public void inform(SolrCore core) {
    this.core = core;
  }

  @Override
  public void init(NamedList<?> args) {
    super.init(args);
    assert codec == null;
    String compressionModeStr = (String) args.get(COMPRESSION_MODE);
    Mode compressionMode;
    if (compressionModeStr != null) {
      try {
        compressionMode = Mode.valueOf(compressionModeStr.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new SolrException(
            ErrorCode.SERVER_ERROR,
            "Invalid compressionMode: '"
                + compressionModeStr
                + "'. Value must be one of "
                + Arrays.toString(Mode.values()));
      }
      log.debug("Using compressionMode: {}", compressionMode);
    } else {
      compressionMode = SOLR_DEFAULT_COMPRESSION_MODE;
      log.debug("Using default compressionMode: {}", compressionMode);
    }
    codec =
        new Lucene912Codec(compressionMode) {
          @Override
          public PostingsFormat getPostingsFormatForField(String field) {
            final SchemaField schemaField = core.getLatestSchema().getFieldOrNull(field);
            if (schemaField != null) {
              String postingsFormatName = schemaField.getPostingsFormat();
              if (postingsFormatName != null) {
                return PostingsFormat.forName(postingsFormatName);
              }
            }
            return super.getPostingsFormatForField(field);
          }

          @Override
          public DocValuesFormat getDocValuesFormatForField(String field) {
            final SchemaField schemaField = core.getLatestSchema().getFieldOrNull(field);
            if (schemaField != null) {
              String docValuesFormatName = schemaField.getDocValuesFormat();
              if (docValuesFormatName != null) {
                return DocValuesFormat.forName(docValuesFormatName);
              }
            }
            return super.getDocValuesFormatForField(field);
          }

          @Override
          public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
            final SchemaField schemaField = core.getLatestSchema().getFieldOrNull(field);
            FieldType fieldType = (schemaField == null ? null : schemaField.getType());
            if (fieldType instanceof DenseVectorField vectorType) {
              String knnAlgorithm = vectorType.getKnnAlgorithm();
              if (DenseVectorField.HNSW_ALGORITHM.equals(knnAlgorithm)) {
                int maxConn = vectorType.getHnswMaxConn();
                int beamWidth = vectorType.getHnswBeamWidth();
                var delegate = new Lucene99HnswVectorsFormat(maxConn, beamWidth);
                return new SolrDelegatingKnnVectorsFormat(delegate, vectorType.getDimension());
              } else {
                throw new SolrException(
                    ErrorCode.SERVER_ERROR, knnAlgorithm + " KNN algorithm is not supported");
              }
            }
            return super.getKnnVectorsFormatForField(field);
          }
        };
  }

  @Override
  public Codec getCodec() {
    assert core != null : "inform must be called first";
    return codec;
  }

  /**
   * This class exists because {@link Lucene99HnswVectorsFormat#getMaxDimensions(String)} method is
   * final and we need to workaround that constraint to allow more than the default number of
   * dimensions
   */
  private static final class SolrDelegatingKnnVectorsFormat extends KnnVectorsFormat {
    private final KnnVectorsFormat delegate;
    private final int maxDimensions;

    public SolrDelegatingKnnVectorsFormat(KnnVectorsFormat delegate, int maxDimensions) {
      super(delegate.getName());
      this.delegate = delegate;
      this.maxDimensions = maxDimensions;
    }

    @Override
    public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
      return delegate.fieldsWriter(state);
    }

    @Override
    public KnnVectorsReader fieldsReader(SegmentReadState state) throws IOException {
      return delegate.fieldsReader(state);
    }

    @Override
    public int getMaxDimensions(String fieldName) {
      return maxDimensions;
    }
  }
}
