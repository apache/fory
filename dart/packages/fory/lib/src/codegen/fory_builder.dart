import 'package:build/build.dart';
import 'package:source_gen/source_gen.dart';

import 'package:fory/src/codegen/fory_generator.dart';

Builder foryBuilder(BuilderOptions options) =>
    PartBuilder([ForyGenerator()], '.fory.dart');
