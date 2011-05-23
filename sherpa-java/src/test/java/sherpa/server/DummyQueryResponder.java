/*
 * Copyright 2011 Revelytix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sherpa.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.avro.AvroRemoteException;

import sherpa.protocol.DataRequest;
import sherpa.protocol.DataResponse;
import sherpa.protocol.ErrorResponse;
import sherpa.protocol.IRI;
import sherpa.protocol.Query;
import sherpa.protocol.QueryRequest;
import sherpa.protocol.QueryResponse;
import sherpa.protocol.ReasonCode;

public class DummyQueryResponder implements Query {

  private final int rows;

  public DummyQueryResponder(int rows) {
    this.rows = rows;
  }
  
  @Override
  public QueryResponse query(QueryRequest query) throws AvroRemoteException,
      ErrorResponse {
  
    System.out.println("Server got query request for " + query.sparql);
    
    QueryResponse response = new QueryResponse();
    response.queryId = "1";
    response.vars = new ArrayList<CharSequence>();
    response.vars.add("x");
    response.vars.add("y");

    System.out.println("Server sending query response");
    return response;
  }

  private List<List<Object>> batch(int begin, int size) {
    List<List<Object>> tuples = new ArrayList<List<Object>>();
    for(int i=begin; i<begin+size; i++) {
      List<Object> tuple = new ArrayList<Object>();
      IRI iri = new IRI();
      iri.iri = "http://foobar.baz/this/uri/" + i;
      tuple.add(iri);
      tuple.add(i);
      tuples.add(tuple);
    }
    return tuples;
  }
  
  @Override
  public DataResponse data(DataRequest dataRequest) throws AvroRemoteException,
      ErrorResponse {
    
    System.out.println("Server got data request for " + dataRequest.startRow);
    
    if(rows == 0) {
      DataResponse response = new DataResponse();
      response.queryId = dataRequest.queryId;
      response.startRow = 1;
      response.more = false;
      response.data = Collections.emptyList();
      System.out.println("Server sending empty response for 0 row result.");
      return response;
      
    } else if(dataRequest.startRow <= rows) {
      DataResponse response = new DataResponse();
      response.queryId = dataRequest.queryId;
      response.startRow = dataRequest.startRow;
      
      int size = dataRequest.maxSize;
      int last = dataRequest.startRow + size - 1;   // 1-based
      if(last > rows) {
        size = rows - dataRequest.startRow + 1;
      }
      response.data = batch(dataRequest.startRow, size);
      response.more = (response.startRow + size - 1) < rows;
      
      System.out.println("Server sending response for " + dataRequest.startRow + ".." + (dataRequest.startRow+response.data.size()-1));
      return response;
      
    } else {
      ErrorResponse response = new ErrorResponse();
      response.code = ReasonCode.Error;
      response.message = "Invalid request for rows outside the result set.";
      throw response;
    }
  }

}