/*
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

class CodeRules {
  // static const String mustHaveUnnamed = 'the class must have an unnamed constructor';

  static const String consParamsOnlySupportThisAndSuper = '''
  the constructor can only use parameters decorated by this and super'
  one example:
  class A extends B{
    final int _aa;
    final int ab;
    
    A(this._aa, super.bb, {required this.ab, super.ba});
  }
  ''';

  // field can't override
  static const String unsupportFieldOverriding =
      'Classes in the inheritance chain cannot have members with the same name, meaning field overriding is not supported.';

  static const String circularReferenceIncapableRisk =
      "This class's fields (including those from the inheritance chain) are not all basic types, so it may have circular references. To handle this, the class must have a constructor without required parameters, but the constructor specified by @ForyCons does not meet this condition. If you're sure there will be no circular references, use @ForyClass(promiseAcyclic: true).";
}
