package com.apollographql.apollo.compiler

import com.apollographql.apollo.api.internal.QueryDocumentMinifier
import com.apollographql.apollo.compiler.ApolloMetadata.Companion.merge
import com.apollographql.apollo.compiler.codegen.kotlin.GraphQLKompiler
import com.apollographql.apollo.compiler.ir.CodeGenerationContext
import com.apollographql.apollo.compiler.ir.CodeGenerationIR
import com.apollographql.apollo.compiler.ir.TypeDeclaration
import com.apollographql.apollo.compiler.operationoutput.OperationDescriptor
import com.apollographql.apollo.compiler.operationoutput.toJson
import com.apollographql.apollo.compiler.parser.graphql.GraphQLDocumentParser
import com.apollographql.apollo.compiler.parser.introspection.IntrospectionSchema
import com.apollographql.apollo.compiler.parser.sdl.GraphSdlSchema
import com.apollographql.apollo.compiler.parser.sdl.toIntrospectionSchema
import com.squareup.javapoet.JavaFile
import java.io.File

class GraphQLCompiler {
  private data class SchemaInfo(val introspectionSchema: IntrospectionSchema, val schemaPackageName: String)

  private fun getSchemaInfo(roots: Roots, schemaFile: File?, metadata: ApolloMetadata?): SchemaInfo {
    check(schemaFile != null || metadata != null) {
      "ApolloGraphQL: cannot find schema.[json | sdl]"
    }
    check(schemaFile == null || metadata == null) {
      "ApolloGraphQL: You can't define a schema in ${schemaFile?.absolutePath} as one is already defined in a dependency. " +
          "Either remove the schema or the dependency"
    }

    if (schemaFile != null) {
      val introspectionSchema = if (schemaFile.extension == "json") {
        IntrospectionSchema(schemaFile)
      } else {
        GraphSdlSchema(schemaFile).toIntrospectionSchema()
      }

      val schemaPackageName = try {
        roots.filePackageName(schemaFile.absolutePath)
      } catch (e: IllegalArgumentException) {
        // Can happen if the schema is not a child of roots
        ""
      }
      return SchemaInfo(introspectionSchema, schemaPackageName)
    } else if (metadata != null) {
      return SchemaInfo(metadata.schema!!, metadata.options.schemaPackageName!!)
    } else {
      throw IllegalStateException("There should at least be metadata or schemaFile")
    }
  }


  fun write(args: Arguments) {
    args.outputDir.deleteRecursively()
    args.outputDir.mkdirs()

    val roots = Roots(args.rootFolders)
    val metadata = args.metadata.map {
      if (it.isDirectory) {
        ApolloMetadata.readFromDirectory(it)
      } else
        ApolloMetadata.readFromZip(it).apply {
          if (args.rootProjectDir != null) {
            withResolvedFragments(args.rootProjectDir)
          }
        }
    }.merge()

    val (introspectionSchema, schemaPackageName) = getSchemaInfo(roots, args.schemaFile, metadata)

    val packageNameProvider = DefaultPackageNameProvider(
        roots = roots,
        rootPackageName = args.rootPackageName,
        schemaPackageName = schemaPackageName
    )

    val files = args.graphqlFiles
    checkDuplicateFiles(roots, files)

    val defaultAlwaysGenerateTypesMatching: List<String>
    val generateAllScalars: Boolean
    if (metadata == null && args.metadataOutputDir != null) {
      defaultAlwaysGenerateTypesMatching = listOf(".*")
      generateAllScalars = true
    } else {
      defaultAlwaysGenerateTypesMatching = emptyList()
      generateAllScalars = false
    }

    val alwaysGenerateTypesMatching = args.alwaysGenerateTypesMatching ?: defaultAlwaysGenerateTypesMatching
    val extraTypes = introspectionSchema.types.values.filter {
      when (it.kind) {
        IntrospectionSchema.Kind.INPUT_OBJECT,
        IntrospectionSchema.Kind.ENUM -> {
          alwaysGenerateTypesMatching.any { pattern ->
            Regex(pattern).matches(it.name)
          }
        }
        IntrospectionSchema.Kind.SCALAR -> generateAllScalars
        else -> false
      }
    }
        .map { it.name }
        .toSet()

    val ir = GraphQLDocumentParser(
        schema = introspectionSchema,
        packageNameProvider = packageNameProvider,
        incomingFragments = metadata?.fragments ?: emptyList(),
        incomingTypes = metadata?.options?.generatedTypes ?: emptySet(),
        extraTypes = extraTypes
    ).parse(files)

    val operationOutput = ir.operations.map {
      OperationDescriptor(
          name = it.operationName,
          packageName = it.packageName,
          filePath = it.filePath,
          source = QueryDocumentMinifier.minify(it.sourceWithFragments)
      )
    }.let {
      args.operationOutputGenerator.generate(it)
    }
    check(operationOutput.size == ir.operations.size) {
      """The number of operation IDs (${operationOutput.size}) should match the number of operations (${ir.operations.size}).
        |Check that all your IDs are unique.
      """.trimMargin()
    }

    if (args.operationOutputFile != null) {
      args.operationOutputFile.writeText(operationOutput.toJson("  "))
    }

    if (args.metadataOutputDir != null) {
      val outgoingMetadata = ApolloMetadata(
          schema = if (metadata == null) introspectionSchema else null,
          options = ApolloMetadata.Options(
              schemaPackageName = schemaPackageName,
              moduleName = args.moduleName,
              generatedTypes = ir.typesUsed.map { it.name }.toSet()
          ),
          fragments = ir.fragments
      ).apply {
        if (args.rootProjectDir != null) {
          withRelativeFragments(args.rootProjectDir)
        }
      }
      outgoingMetadata.writeTo(args.metadataOutputDir)
    }

    if (args.generateKotlinModels) {
      GraphQLKompiler(
          ir = ir,
          customTypeMap = args.customTypeMap,
          operationOutput = operationOutput,
          useSemanticNaming = args.useSemanticNaming,
          generateAsInternal = args.generateAsInternal,
          kotlinMultiPlatformProject = args.kotlinMultiPlatformProject,
          enumAsSealedClassPatternFilters = args.enumAsSealedClassPatternFilters.map { it.toRegex() }
      ).write(args.outputDir)
    } else {
      val customTypeMap = args.customTypeMap.supportedTypeMap(ir.typesUsed)
      val context = CodeGenerationContext(
          reservedTypeNames = emptyList(),
          typeDeclarations = ir.typesUsed,
          customTypeMap = customTypeMap,
          operationOutput = operationOutput,
          nullableValueType = args.nullableValueType,
          ir = ir,
          useSemanticNaming = args.useSemanticNaming,
          generateModelBuilder = args.generateModelBuilder,
          useJavaBeansSemanticNaming = args.useJavaBeansSemanticNaming,
          suppressRawTypesWarning = args.suppressRawTypesWarning,
          generateVisitorForPolymorphicDatatypes = args.generateVisitorForPolymorphicDatatypes
      )

      ir.writeJavaFiles(
          context = context,
          outputDir = args.outputDir
      )
    }
  }

  private fun CodeGenerationIR.writeJavaFiles(context: CodeGenerationContext, outputDir: File) {
    fragments.forEach {
      val typeSpec = it.toTypeSpec(context.copy())
      JavaFile
          .builder(context.ir.fragmentsPackageName, typeSpec)
          .addFileComment(AUTO_GENERATED_FILE)
          .build()
          .writeTo(outputDir)
    }

    typesUsed.supportedTypeDeclarations().forEach {
      val typeSpec = it.toTypeSpec(context.copy())
      JavaFile
          .builder(context.ir.typesPackageName, typeSpec)
          .addFileComment(AUTO_GENERATED_FILE)
          .build()
          .writeTo(outputDir)
    }

    if (context.customTypeMap.isNotEmpty()) {
      val typeSpec = CustomEnumTypeSpecBuilder(context.copy()).build()
      JavaFile
          .builder(context.ir.typesPackageName, typeSpec)
          .addFileComment(AUTO_GENERATED_FILE)
          .build()
          .writeTo(outputDir)
    }

    operations.map { OperationTypeSpecBuilder(it, fragments, context.useSemanticNaming) }
        .forEach {
          val packageName = it.operation.packageName
          val typeSpec = it.toTypeSpec(context.copy())
          JavaFile
              .builder(packageName, typeSpec)
              .addFileComment(AUTO_GENERATED_FILE)
              .build()
              .writeTo(outputDir)
        }
  }

  private fun List<TypeDeclaration>.supportedTypeDeclarations() =
      filter { it.kind == TypeDeclaration.KIND_ENUM || it.kind == TypeDeclaration.KIND_INPUT_OBJECT_TYPE }

  private fun Map<String, String>.supportedTypeMap(typeDeclarations: List<TypeDeclaration>): Map<String, String> {
    return typeDeclarations.filter { it.kind == TypeDeclaration.KIND_SCALAR_TYPE }
        .associate { it.name to (this[it.name] ?: ClassNames.OBJECT.toString()) }
  }

  companion object {
    private const val AUTO_GENERATED_FILE = "AUTO-GENERATED FILE. DO NOT MODIFY.\n\n" +
        "This class was automatically generated by Apollo GraphQL plugin from the GraphQL queries it found.\n" +
        "It should not be modified by hand.\n"

    /**
     * Check for duplicates files. This can happen with Android variants
     */
    private fun checkDuplicateFiles(roots: Roots, files: Set<File>) {
      val map = files.groupBy { roots.filePackageName(it.normalize().absolutePath) to it.nameWithoutExtension }

      map.values.forEach {
        require(it.size == 1) {
          "ApolloGraphQL: duplicate(s) graphql file(s) found:\n" +
              it.map { it.absolutePath }.joinToString("\n")
        }
      }
    }
  }

  /**
   * For more details about the fields defined here, check the gradle plugin
   */
  data class Arguments(
      /**
       * The rootFolders where the graphqlFiles are located. The package name of each individual graphql query
       * will be the relative path to the root folders
       */
      val rootFolders: List<File>,
      /**
       * The files where the graphql queries/fragments are located
       */
      val graphqlFiles: Set<File>,
      /**
       * The schema. Can be either a SDL schema or an introspection schema.
       * If null, the schema, metedata must not be empty
       */
      val schemaFile: File?,
      /**
       * The folder where to generate the sources
       */
      val outputDir: File,

      //========== multi-module ============

      /**
       * A list of zip files or directories containing metadata from previous compilations
       */
      val metadata: List<File> = emptyList(),
      /**
       * The moduleName for this metadata. Used for debugging purposes
       */
      val moduleName: String = "?",
      /**
       * Optional rootProjectDir. If it exists:
       * - when writing metadata, the compiler will output relative path to rootProjectDir
       * - when reading metadata, the compiler will lookup the actual file
       * This allows to lookup the real fragment file if all compilation units belong to the same project
       * and output nicer error messages
       */
      val rootProjectDir: File? = null,
      /**
       * The directory where to write the metadata
       */
      val metadataOutputDir: File? = null,
      /**
       * Additional types to generate. This will generate this type and all types this type depends on.
       */
      val alwaysGenerateTypesMatching: Set<String>? = null,

      //========== operation-output ============

      /**
       * the file where to write the operationOutput
       * if null, no operationOutput is written
       */
      val operationOutputFile: File? = null,
      /**
       * the OperationOutputGenerator used to generate operation Ids
       */
      val operationOutputGenerator: OperationOutputGenerator = OperationOutputGenerator.DefaultOperationOuputGenerator(OperationIdGenerator.Sha256()),

      //========== global codegen options ============

      val rootPackageName: String = "",
      val generateKotlinModels: Boolean = false,
      val customTypeMap: Map<String, String> = emptyMap(),
      val useSemanticNaming: Boolean = true,
      val generateAsInternal: Boolean = false,

      //========== Kotlin codegen options ============

      val kotlinMultiPlatformProject: Boolean = false,
      val enumAsSealedClassPatternFilters: Set<String> = emptySet(),

      //========== Java codegen options ============

      val nullableValueType: NullableValueType = NullableValueType.ANNOTATED,
      val generateModelBuilder: Boolean = false,
      val useJavaBeansSemanticNaming: Boolean = false,
      val suppressRawTypesWarning: Boolean = false,
      val generateVisitorForPolymorphicDatatypes: Boolean = false
  )
}
