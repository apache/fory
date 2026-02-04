// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package idl_test

import (
	"reflect"
	"testing"

	addressbook "github.com/apache/fory/integration_tests/idl_tests/go/addressbook/generated"
	rootpkg "github.com/apache/fory/integration_tests/idl_tests/go/root/generated"
	treepkg "github.com/apache/fory/integration_tests/idl_tests/go/tree/generated"
)

func buildRootHolder() rootpkg.MultiHolder {
	owner := addressbook.Person{
		Name:   "Alice",
		Id:     123,
		Tags:   []string{},
		Scores: map[string]int32{},
		Phones: []addressbook.Person_PhoneNumber{},
		Pet: addressbook.DogAnimal(&addressbook.Dog{
			Name:       "Rex",
			BarkVolume: 5,
		}),
	}
	book := addressbook.AddressBook{
		People:       []addressbook.Person{owner},
		PeopleByName: map[string]addressbook.Person{owner.Name: owner},
	}
	root := treepkg.TreeNode{
		Id:       "root",
		Name:     "root",
		Children: []*treepkg.TreeNode{},
	}
	return rootpkg.MultiHolder{
		Book:  &book,
		Root:  &root,
		Owner: &owner,
	}
}

func TestRootToBytesFromBytes(t *testing.T) {
	multi := buildRootHolder()
	multiBytes, err := multi.ToBytes()
	if err != nil {
		t.Fatalf("root to_bytes: %v", err)
	}
	var decodedMulti rootpkg.MultiHolder
	if err := decodedMulti.FromBytes(multiBytes); err != nil {
		t.Fatalf("root from_bytes: %v", err)
	}
	if !reflect.DeepEqual(multi, decodedMulti) {
		t.Fatalf("root to_bytes roundtrip mismatch:\noriginal=%#v\ndecoded=%#v", multi, decodedMulti)
	}
}
