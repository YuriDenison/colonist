/*
 * Copyright 2019 Michael Rozumyanskiy
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

package io.michaelrocks.colonist.processor.commons

import io.michaelrocks.colonist.processor.descriptors.FieldDescriptor
import io.michaelrocks.colonist.processor.descriptors.MethodDescriptor
import io.michaelrocks.grip.mirrors.FieldMirror
import io.michaelrocks.grip.mirrors.MethodMirror

fun MethodMirror.toMethodDescriptor(): MethodDescriptor {
  return MethodDescriptor(name, type)
}

fun FieldMirror.toFieldDescriptor(): FieldDescriptor {
  return FieldDescriptor(name, type)
}