/*
// Licensed to Julian Hyde under one or more contributor license
// agreements. See the NOTICE file distributed with this work for
// additional information regarding copyright ownership.
//
// Julian Hyde licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except in
// compliance with the License. You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
*/
package org.eigenbase.sql.type;

import java.util.List;

import org.eigenbase.reltype.*;
import org.eigenbase.sql.*;

import com.google.common.collect.ImmutableList;

/**
 * ExplicitOperandTypeInferences implements {@link SqlOperandTypeInference} by
 * explicity supplying a type for each parameter.
 */
public class ExplicitOperandTypeInference implements SqlOperandTypeInference {
  //~ Instance fields --------------------------------------------------------

  private final ImmutableList<RelDataType> paramTypes;

  //~ Constructors -----------------------------------------------------------

  public ExplicitOperandTypeInference(List<RelDataType> paramTypes) {
    this.paramTypes = ImmutableList.copyOf(paramTypes);
  }

  //~ Methods ----------------------------------------------------------------

  public void inferOperandTypes(
      SqlCallBinding callBinding,
      RelDataType returnType,
      RelDataType[] operandTypes) {
    assert operandTypes.length == paramTypes.size();
    paramTypes.toArray(operandTypes);
  }
}

// End ExplicitOperandTypeInference.java