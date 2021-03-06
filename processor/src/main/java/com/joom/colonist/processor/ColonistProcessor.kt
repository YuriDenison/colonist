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

package com.joom.colonist.processor

import com.joom.colonist.processor.analysis.AnnotationIndex
import com.joom.colonist.processor.analysis.ColonyMarkerParser
import com.joom.colonist.processor.analysis.ColonyMarkerParserImpl
import com.joom.colonist.processor.analysis.ColonyParser
import com.joom.colonist.processor.analysis.ColonyParserImpl
import com.joom.colonist.processor.analysis.SettlerAcceptorParserImpl
import com.joom.colonist.processor.analysis.SettlerDiscoverer
import com.joom.colonist.processor.analysis.SettlerDiscovererImpl
import com.joom.colonist.processor.analysis.SettlerParserImpl
import com.joom.colonist.processor.analysis.SettlerProducerParserImpl
import com.joom.colonist.processor.analysis.SettlerSelectorParserImpl
import com.joom.colonist.processor.commons.StandaloneClassWriter
import com.joom.colonist.processor.commons.Types
import com.joom.colonist.processor.commons.closeQuietly
import com.joom.colonist.processor.generation.ColonyPatcher
import com.joom.colonist.processor.generation.SettlerPatcher
import com.joom.colonist.processor.logging.getLogger
import com.joom.colonist.processor.model.Colony
import com.joom.colonist.processor.model.ColonyMarker
import com.joom.colonist.processor.model.Settler
import com.joom.colonist.processor.model.SettlerSelector
import io.michaelrocks.grip.Grip
import io.michaelrocks.grip.GripFactory
import io.michaelrocks.grip.classes
import io.michaelrocks.grip.io.FileSource
import io.michaelrocks.grip.io.IoFactory
import io.michaelrocks.grip.mirrors.Annotated
import io.michaelrocks.grip.mirrors.Type
import io.michaelrocks.grip.mirrors.getObjectTypeByInternalName
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import java.io.Closeable
import java.io.File

class ColonistProcessor(
  inputs: List<File>,
  outputs: List<File>,
  private val grip: Grip,
  private val annotationIndex: AnnotationIndex,
  private val colonyMarkerParser: ColonyMarkerParser,
  private val colonyParser: ColonyParser,
  private val settlerDiscoverer: SettlerDiscoverer,
  private val errorReporter: ErrorReporter
) : Closeable {

  private val logger = getLogger()

  private val fileSourcesAndSinks = inputs.zip(outputs) { input, output ->
    val source = IoFactory.createFileSource(input)
    val sink = IoFactory.createFileSink(input, output)
    source to sink
  }

  fun processClasses() {
    val colonies = findColonies()
    checkErrors()
    copyAndPatchClasses(colonies)
  }

  override fun close() {
    fileSourcesAndSinks.forEach {
      it.first.closeQuietly()
      it.second.closeQuietly()
    }
  }

  private fun findColonies(): Collection<Colony> {
    val markers = findColonyMarkers()
    val markersWithSettlers = findSettlersForColonyMarkers(markers)
    return findColonies(markersWithSettlers)
  }

  private fun findColonyMarkers(): Collection<ColonyMarker> {
    val colonyAnnotationTypes = annotationIndex.findClassesWithAnnotation(Types.COLONY_TYPE)
    return colonyAnnotationTypes.mapNotNull { colonyAnnotationType ->
      try {
        colonyMarkerParser.parseColonyMarker(colonyAnnotationType)
      } catch (exception: Exception) {
        errorReporter.reportError(exception)
        null
      }
    }
  }

  private fun findSettlersForColonyMarkers(colonyMarkers: Collection<ColonyMarker>): Collection<ColonyMarkerWithSettlers> {
    val cache = HashMap<SettlerSelector, Collection<Settler>>()
    return colonyMarkers.map { marker ->
      val selector = marker.settlerSelector
      val settlers = cache.getOrPut(selector) {
        settlerDiscoverer.discoverSettlers(selector)
      }
      ColonyMarkerWithSettlers(marker, settlers)
    }
  }

  private fun findColonies(colonyMarkersWithSettlers: Collection<ColonyMarkerWithSettlers>): Collection<Colony> {
    return colonyMarkersWithSettlers.flatMap { findColonies(it.colonyMarker, it.settlers) }
  }

  private fun findColonies(colonyMarker: ColonyMarker, settlers: Collection<Settler>): Collection<Colony> {
    val colonyTypes = annotationIndex.findClassesWithAnnotation(colonyMarker.type)
    return colonyTypes.mapNotNull { colonyType ->
      try {
        colonyParser.parseColony(colonyType, colonyMarker, settlers)
      } catch (exception: Exception) {
        errorReporter.reportError(exception)
        null
      }
    }
  }

  private fun copyAndPatchClasses(colonies: Collection<Colony>) {
    val colonyTypeToColoniesMap = colonies.groupBy { it.type }
    fileSourcesAndSinks.forEach { (fileSource, fileSink) ->
      logger.debug("Copy from {} to {}", fileSource, fileSink)
      fileSource.listFiles { path, type ->
        logger.debug("Copy file {} of type {}", path, type)
        when (type) {
          FileSource.EntryType.CLASS -> {
            val classReader = ClassReader(fileSource.readFile(path))
            val classWriter = StandaloneClassWriter(
              classReader, ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES, grip.classRegistry
            )
            val classType = getObjectTypeByInternalName(classReader.className)
            val classVisitor = createClassVisitorForType(classType, classWriter, colonies, colonyTypeToColoniesMap)
            classReader.accept(classVisitor, ClassReader.SKIP_FRAMES)
            fileSink.createFile(path, classWriter.toByteArray())
          }

          FileSource.EntryType.FILE -> fileSink.createFile(path, fileSource.readFile(path))
          FileSource.EntryType.DIRECTORY -> fileSink.createDirectory(path)
        }
      }

      fileSink.flush()
    }

    checkErrors()
  }

  private fun createClassVisitorForType(
    type: Type.Object,
    input: ClassVisitor,
    colonies: Collection<Colony>,
    colonyTypeToColoniesMap: Map<Type.Object, Collection<Colony>>
  ): ClassVisitor {
    val visitor = colonyTypeToColoniesMap[type]?.let { ColonyPatcher(input, it) } ?: input
    val isSettler = colonies.any { isSettler(it, type) }
    return if (isSettler) SettlerPatcher(visitor) else visitor
  }

  private fun isSettler(colony: Colony, type: Type.Object): Boolean {
    return colony.settlers.any { it.type == type }
  }

  private fun checkErrors() {
    if (errorReporter.hasErrors()) {
      throw ProcessingException(composeErrorMessage())
    }
  }

  private fun composeErrorMessage(): String {
    return errorReporter.getErrors().joinToString("\n") { it.message.orEmpty() }
  }

  private data class ColonyMarkerWithSettlers(
    val colonyMarker: ColonyMarker,
    val settlers: Collection<Settler>
  )

  companion object {
    fun process(parameters: ColonistParameters) {
      val errorReporter = ErrorReporter()
      val grip = GripFactory.create(parameters.inputs + parameters.classpath + parameters.bootClasspath)
      val annotationIndex = buildAnnotationIndex(grip, parameters.inputs)
      val colonyMarkerParser = ColonyMarkerParserImpl(grip, SettlerSelectorParserImpl, SettlerProducerParserImpl, SettlerAcceptorParserImpl)
      val colonyParser = ColonyParserImpl(grip, errorReporter)
      val settlerParser = SettlerParserImpl(grip, SettlerProducerParserImpl, SettlerAcceptorParserImpl)
      val settlerDiscoverer = SettlerDiscovererImpl(grip, parameters.inputs, settlerParser)

      ColonistProcessor(
        parameters.inputs,
        parameters.outputs,
        grip,
        annotationIndex,
        colonyMarkerParser,
        colonyParser,
        settlerDiscoverer,
        errorReporter
      ).use {
        it.processClasses()
      }
    }

    private fun buildAnnotationIndex(grip: Grip, inputs: List<File>): AnnotationIndex {
      return AnnotationIndex.build {
        val query = grip select classes from inputs where annotated()
        for (mirror in query.execute().classes) {
          for (annotation in mirror.annotations) {
            addAnnotatedType(mirror.type, annotation.type)
          }
        }
      }
    }

    private fun annotated() = { _: Grip, mirror: Annotated -> mirror.annotations.isNotEmpty() }
  }
}
