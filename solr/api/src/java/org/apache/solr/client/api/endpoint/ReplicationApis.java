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
package org.apache.solr.client.api.endpoint;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import java.io.IOException;
import org.apache.solr.client.api.model.FileListResponse;
import org.apache.solr.client.api.model.IndexVersionResponse;
import org.apache.solr.client.api.util.CoreApiParameters;

@Path("/cores/{coreName}/replication")
public interface ReplicationApis {

  @GET
  @CoreApiParameters
  @Path("/indexversion")
  @Operation(
      summary = "Return the index version of the specified core.",
      tags = {"replication"})
  IndexVersionResponse fetchIndexVersion() throws IOException;

  @GET
  @CoreApiParameters
  @Path("/files")
  @Operation(
      summary = "Return the list of index file that make up the specified core.",
      tags = {"replication"})
  FileListResponse fetchFileList(
      @Parameter(description = "The generation number of the index", required = true)
          @QueryParam("generation")
          long gen);
}