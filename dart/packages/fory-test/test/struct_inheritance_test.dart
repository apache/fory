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

import 'package:fory/fory.dart';
import 'package:fory_test/model/inheritance_models.dart';
import 'package:test/test.dart';

void _registerChild(Fory fory, {required bool byName}) {
  InheritanceModelsForyModule.register(
    fory,
    CrossLibraryChild,
    id: byName ? null : 401,
    name: byName ? 'inheritance.CrossLibraryChild' : null,
  );
}

void _registerReferenceLeaf(Fory fory) {
  InheritanceModelsForyModule.register(fory, ReferenceLeaf, id: 402);
}

void _registerReferenceRoot(Fory fory, Type type) {
  InheritanceModelsForyModule.register(
    fory,
    type,
    name: 'inheritance.ReferenceRoot',
  );
}

void _registerSelfRoot(Fory fory, Type type) {
  InheritanceModelsForyModule.register(
    fory,
    type,
    name: 'inheritance.SelfRoot',
  );
}

void _registerCompatibleHierarchy(
  Fory fory, {
  required Type parentType,
  required Type childType,
}) {
  InheritanceModelsForyModule.register(
    fory,
    parentType,
    name: 'inheritance.CompatibleParent',
  );
  InheritanceModelsForyModule.register(
    fory,
    childType,
    name: 'inheritance.CompatibleChild',
  );
}

void main() {
  group('ordinary struct inheritance', () {
    for (final compatible in <bool>[false, true]) {
      test('round trips private hierarchy compatible=$compatible', () {
        final fory = Fory(compatible: compatible);
        _registerChild(fory, byName: compatible);

        final input =
            CrossLibraryChild('base-final', 37, true)
              ..setBasePrivateName('base-private')
              ..setMiddlePrivateName('middle-private')
              ..setMixinCount(9)
              ..publicLabel = 'base-public'
              ..publicMiddleCount = 12
              ..childMutable = 'child';

        final result = fory.deserialize<CrossLibraryChild>(
          fory.serialize(input),
        );

        expect(result.ownerValue, 'base-final');
        expect(result.middleValue, 37);
        expect(result.childFinal, isTrue);
        expect(result.basePrivateName, 'base-private');
        expect(result.middlePrivateName, 'middle-private');
        expect(result.mixinCount, 9);
        expect(result.publicLabel, 'base-public');
        expect(result.publicMiddleCount, 12);
        expect(result.childMutable, 'child');
      });
    }

    for (final compatible in <bool>[false, true]) {
      test('omits inherited private fields compatible=$compatible', () {
        final fory = Fory(compatible: compatible);
        InheritanceModelsForyModule.register(
          fory,
          IgnoringPrivateChild,
          id: compatible ? null : 407,
          name: compatible ? 'inheritance.IgnoringPrivateChild' : null,
        );

        final input =
            IgnoringPrivateChild()
              ..setBasePrivate('base-private')
              ..setMixinPrivate('mixin-private')
              ..setMiddlePrivate('middle-private')
              ..basePublic = 'base-public'
              ..middlePublic = 'middle-public'
              ..childPublic = 'child-public';

        final result = fory.deserialize<IgnoringPrivateChild>(
          fory.serialize(input),
        );

        expect(result.basePrivate, 'base-private-default');
        expect(result.mixinPrivate, 'mixin-private-default');
        expect(result.middlePrivate, 'middle-private-default');
        expect(result.basePublic, 'base-public');
        expect(result.middlePublic, 'middle-public');
        expect(result.childPublic, 'child-public');
      });
    }

    test('passes optional mutable state through construction', () {
      final fory = Fory();
      InheritanceModelsForyModule.register(fory, OptionalMutableChild, id: 405);

      final result = fory.deserialize<OptionalMutableChild>(
        fory.serialize(OptionalMutableChild(7, 19)),
      );

      expect(result.fixed, 7);
      expect(result.inheritedMutable, 19);
      expect(result.observedAtConstruction, 19);
    });

    test('analyzes optional flow for all-writable state', () {
      final fory = Fory();
      InheritanceModelsForyModule.register(
        fory,
        AllWritableOptionalChild,
        id: 406,
      );

      final result = fory.deserialize<AllWritableOptionalChild>(
        fory.serialize(AllWritableOptionalChild(23)),
      );

      expect(result.inheritedMutable, 23);
      expect(result.observedAtConstruction, 23);
    });

    for (final compatible in <bool>[false, true]) {
      test('matches flat and inherited schemas compatible=$compatible', () {
        final privateFory = Fory(compatible: compatible);
        final publicFory = Fory(compatible: compatible);
        final flatFory = Fory(compatible: compatible);
        InheritanceModelsForyModule.register(
          privateFory,
          XlangInheritedChild,
          id: 404,
        );
        InheritanceModelsForyModule.register(
          publicFory,
          PublicXlangInheritedChild,
          id: 404,
        );
        InheritanceModelsForyModule.register(
          flatFory,
          FlatXlangInheritedStruct,
          id: 404,
        );

        final privateValue =
            XlangInheritedChild()
              ..childInt = 11
              ..childFlag = true
              ..childText = 'child'
              ..setParentValues(
                parentInt: 22,
                parentFlag: false,
                parentText: 'parent',
              );
        final publicValue =
            PublicXlangInheritedChild()
              ..childInt = 11
              ..parentInt = 22
              ..childFlag = true
              ..parentFlag = false
              ..childText = 'child'
              ..parentText = 'parent';
        final flatValue =
            FlatXlangInheritedStruct()
              ..childInt = 11
              ..parentInt = 22
              ..childFlag = true
              ..parentFlag = false
              ..childText = 'child'
              ..parentText = 'parent';

        final privateBytes = privateFory.serialize(privateValue);
        final publicBytes = publicFory.serialize(publicValue);
        final flatBytes = flatFory.serialize(flatValue);
        expect(privateBytes, publicBytes);
        expect(privateBytes, flatBytes);

        final privateResult = privateFory.deserialize<XlangInheritedChild>(
          privateBytes,
        );
        final publicResult = publicFory.deserialize<PublicXlangInheritedChild>(
          publicBytes,
        );
        final flatResult = flatFory.deserialize<FlatXlangInheritedStruct>(
          flatBytes,
        );
        expect(privateResult.parentInt, 22);
        expect(privateResult.parentText, 'parent');
        expect(publicResult.parentInt, 22);
        expect(publicResult.parentText, 'parent');
        expect(flatResult.parentInt, 22);
        expect(flatResult.parentText, 'parent');
      });
    }
  });

  group('compatible inherited evolution', () {
    test('reads added and missing inherited fields both directions', () {
      final oldFory = Fory(compatible: true);
      final newFory = Fory(compatible: true);
      _registerCompatibleHierarchy(
        oldFory,
        parentType: CompatibleOldParent,
        childType: CompatibleOldChild,
      );
      _registerCompatibleHierarchy(
        newFory,
        parentType: CompatibleNewParent,
        childType: CompatibleNewChild,
      );

      final oldInput =
          CompatibleOldChild()
            ..inheritedStable = 'old-parent'
            ..childStable = 'old-child';
      final newResult = newFory.deserialize<CompatibleNewChild>(
        oldFory.serialize(oldInput),
      );
      expect(newResult.inheritedStable, 'old-parent');
      expect(newResult.inheritedAdded, 'new-default');
      expect(newResult.childStable, 'old-child');

      final newInput =
          CompatibleNewChild()
            ..inheritedStable = 'new-parent'
            ..inheritedAdded = 'new-only'
            ..childStable = 'new-child';
      final oldResult = oldFory.deserialize<CompatibleOldChild>(
        newFory.serialize(newInput),
      );
      expect(oldResult.inheritedStable, 'new-parent');
      expect(oldResult.childStable, 'new-child');
    });

    test('registers each parent independently', () {
      final oldFory = Fory(compatible: true);
      InheritanceModelsForyModule.register(
        oldFory,
        CompatibleOldParent,
        name: 'inheritance.CompatibleParent',
      );
      final oldParent = CompatibleOldParent()..inheritedStable = 'old-parent';
      final oldResult = oldFory.deserialize<CompatibleOldParent>(
        oldFory.serialize(oldParent),
      );
      expect(oldResult.inheritedStable, 'old-parent');

      final newFory = Fory(compatible: true);
      InheritanceModelsForyModule.register(
        newFory,
        CompatibleNewParent,
        name: 'inheritance.CompatibleParent',
      );
      final newParent =
          CompatibleNewParent()
            ..inheritedStable = 'new-parent'
            ..inheritedAdded = 'new-field';
      final newResult = newFory.deserialize<CompatibleNewParent>(
        newFory.serialize(newParent),
      );
      expect(newResult.inheritedStable, 'new-parent');
      expect(newResult.inheritedAdded, 'new-field');
    });
  });

  group('equivalent flat references', () {
    for (final compatible in <bool>[false, true]) {
      test('matches inherited shared refs compatible=$compatible', () {
        final inheritedFory = Fory(compatible: compatible);
        final flatFory = Fory(compatible: compatible);
        _registerReferenceLeaf(inheritedFory);
        _registerReferenceLeaf(flatFory);
        _registerReferenceRoot(inheritedFory, InheritedReferenceModel);
        _registerReferenceRoot(flatFory, FlatReferenceModel);

        final inheritedLeaf = ReferenceLeaf()..label = 'shared';
        final flatLeaf = ReferenceLeaf()..label = 'shared';
        final inherited =
            InheritedReferenceModel()
              ..first = inheritedLeaf
              ..second = inheritedLeaf
              ..nested = <ReferenceLeaf>[inheritedLeaf, inheritedLeaf];
        final flat =
            FlatReferenceModel()
              ..first = flatLeaf
              ..second = flatLeaf
              ..nested = <ReferenceLeaf>[flatLeaf, flatLeaf];

        final inheritedBytes = inheritedFory.serialize(inherited);
        final flatBytes = flatFory.serialize(flat);
        expect(inheritedBytes, flatBytes);

        final inheritedResult = inheritedFory
            .deserialize<InheritedReferenceModel>(inheritedBytes);
        final flatResult = flatFory.deserialize<FlatReferenceModel>(flatBytes);
        expect(
          identical(inheritedResult.first, inheritedResult.second),
          isTrue,
        );
        expect(
          identical(inheritedResult.first, inheritedResult.nested.first),
          isTrue,
        );
        expect(identical(flatResult.first, flatResult.second), isTrue);
        expect(identical(flatResult.first, flatResult.nested.first), isTrue);
      });

      test('matches inherited self refs compatible=$compatible', () {
        final inheritedFory = Fory(compatible: compatible);
        final flatFory = Fory(compatible: compatible);
        _registerSelfRoot(inheritedFory, InheritedSelfReference);
        _registerSelfRoot(flatFory, FlatSelfReference);

        final inherited = InheritedSelfReference();
        inherited.self = inherited;
        final flat = FlatSelfReference();
        flat.self = flat;

        final inheritedBytes = inheritedFory.serialize(inherited);
        final flatBytes = flatFory.serialize(flat);
        expect(inheritedBytes, flatBytes);

        final inheritedResult = inheritedFory
            .deserialize<InheritedSelfReference>(inheritedBytes);
        final flatResult = flatFory.deserialize<FlatSelfReference>(flatBytes);
        expect(identical(inheritedResult, inheritedResult.self), isTrue);
        expect(identical(flatResult, flatResult.self), isTrue);
      });
    }
  });
}
