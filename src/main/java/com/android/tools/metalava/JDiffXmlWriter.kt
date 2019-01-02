/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.metalava

import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.model.Codebase
import com.android.tools.metalava.model.ConstructorItem
import com.android.tools.metalava.model.FieldItem
import com.android.tools.metalava.model.Item
import com.android.tools.metalava.model.MethodItem
import com.android.tools.metalava.model.PackageItem
import com.android.tools.metalava.model.PropertyItem
import com.android.tools.metalava.model.TypeItem
import com.android.tools.metalava.model.psi.CodePrinter
import com.android.tools.metalava.model.visitors.ApiVisitor
import com.android.utils.XmlUtils
import java.io.PrintWriter
import java.util.function.Predicate

/**
 * Writes out an XML format in the JDiff schema: See $ANDROID/external/jdiff/src/api.xsd
 * (though limited to the same subset as generated by Doclava; and using the same
 * conventions for the unspecified parts of the schema, such as what value to put
 * in the deprecated string. It also uses the same XML formatting.)
 *
 * Known differences: Doclava seems to skip enum fields. We don't do that.
 * Doclava seems to skip type parameters; we do the same.
 */
class JDiffXmlWriter(
    private val writer: PrintWriter,
    filterEmit: Predicate<Item>,
    filterReference: Predicate<Item>,
    private val preFiltered: Boolean
) : ApiVisitor(
    visitConstructorsAsMethods = false,
    nestInnerClasses = false,
    inlineInheritedFields = true,
    methodComparator = MethodItem.comparator,
    fieldComparator = FieldItem.comparator,
    filterEmit = filterEmit,
    filterReference = filterReference,
    showUnannotated = options.showUnannotated
) {
    override fun visitCodebase(codebase: Codebase) {
        writer.println("<api>")
    }

    override fun afterVisitCodebase(codebase: Codebase) {
        writer.println("</api>")
    }

    override fun visitPackage(pkg: PackageItem) {
        // Note: we apparently don't write package annotations anywhere
        writer.println("<package name=\"${pkg.qualifiedName()}\"\n>")
    }

    override fun afterVisitPackage(pkg: PackageItem) {
        writer.println("</package>")
    }

    override fun visitClass(cls: ClassItem) {
        writer.print('<')
        // XML format does not seem to special case annotations or enums
        if (cls.isInterface()) {
            writer.print("interface")
        } else {
            writer.print("class")
        }
        writer.print(" name=\"")
        writer.print(cls.fullName())
        // Note - to match doclava we don't write out the type parameter list
        // (cls.typeParameterList()) in JDiff files!
        writer.print("\"")

        writeSuperClassAttribute(cls)

        val modifiers = cls.modifiers
        writer.print("\n abstract=\"")
        writer.print(modifiers.isAbstract())
        writer.print("\"\n static=\"")
        writer.print(modifiers.isStatic())
        writer.print("\"\n final=\"")
        writer.print(modifiers.isFinal())
        writer.print("\"\n deprecated=\"")
        writer.print(deprecation(cls))
        writer.print("\"\n visibility=\"")
        writer.print(modifiers.getVisibilityModifiers())
        writer.println("\"\n>")

        writeInterfaceList(cls)

        if (cls.isEnum() && compatibility.defaultEnumMethods) {
            writer.println(
                """
                <method name="valueOf"
                 return="${cls.qualifiedName()}"
                 abstract="false"
                 native="false"
                 synchronized="false"
                 static="true"
                 final="false"
                 visibility="public"
                >
                <parameter name="null" type="java.lang.String">
                </parameter>
                </method>
                <method name="values"
                 return="${cls.qualifiedName()}[]"
                 abstract="false"
                 native="false"
                 synchronized="false"
                 static="true"
                 final="true"
                 visibility="public"
                >
                </method>""".trimIndent()
            )
        }
    }

    fun deprecation(item: Item): String {
        return if (item.deprecated) {
            "deprecated"
        } else {
            "not deprecated"
        }
    }

    override fun afterVisitClass(cls: ClassItem) {
        writer.print("</")
        if (cls.isInterface()) {
            writer.print("interface")
        } else {
            writer.print("class")
        }
        writer.println(">")
    }

    override fun visitConstructor(constructor: ConstructorItem) {
        val modifiers = constructor.modifiers
        writer.print("<constructor name=\"")
        writer.print(constructor.containingClass().fullName())
        writer.print("\"\n type=\"")
        writer.print(constructor.containingClass().qualifiedName())
        writer.print("\"\n static=\"")
        writer.print(modifiers.isStatic())
        writer.print("\"\n final=\"")
        writer.print(modifiers.isFinal())
        writer.print("\"\n deprecated=\"")
        writer.print(deprecation(constructor))
        writer.print("\"\n visibility=\"")
        writer.print(modifiers.getVisibilityModifiers())
        writer.println("\"\n>")

        // Note - to match doclava we don't write out the type parameter list
        // (constructor.typeParameterList()) in JDiff files!

        writeParameterList(constructor)
        writeThrowsList(constructor)
        writer.println("</constructor>")
    }

    override fun visitField(field: FieldItem) {
        if (field.isEnumConstant() && compatibility.xmlSkipEnumFields) {
            return
        }

        val modifiers = field.modifiers
        val initialValue = field.initialValue(true)
        val value = if (initialValue != null) {
            if (initialValue is Char && compatibility.xmlCharAsInt) {
                initialValue.toInt().toString()
            } else {
                escapeAttributeValue(CodePrinter.constantToSource(initialValue))
            }
        } else null

        val fullTypeName = escapeAttributeValue(field.type().toTypeString())

        writer.print("<field name=\"")
        writer.print(field.name())
        writer.print("\"\n type=\"")
        writer.print(fullTypeName)
        writer.print("\"\n transient=\"")
        writer.print(modifiers.isTransient())
        writer.print("\"\n volatile=\"")
        writer.print(modifiers.isVolatile())
        if (value != null) {
            writer.print("\"\n value=\"")
            writer.print(value)
        } else if (compatibility.xmlShowArrayFieldsAsNull && (field.type().isArray())) {
            writer.print("\"\n value=\"null")
        }

        writer.print("\"\n static=\"")
        writer.print(modifiers.isStatic())
        writer.print("\"\n final=\"")
        writer.print(modifiers.isFinal())
        writer.print("\"\n deprecated=\"")
        writer.print(deprecation(field))
        writer.print("\"\n visibility=\"")
        writer.print(modifiers.getVisibilityModifiers())
        writer.println("\"\n>")

        writer.println("</field>")
    }

    override fun visitProperty(property: PropertyItem) {
        // Not supported by JDiff
    }

    override fun visitMethod(method: MethodItem) {
        val modifiers = method.modifiers

        if (method.containingClass().isAnnotationType() && compatibility.xmlSkipAnnotationMethods) {
            return
        }

        // Note - to match doclava we don't write out the type parameter list
        // (method.typeParameterList()) in JDiff files!

        writer.print("<method name=\"")
        writer.print(method.name())
        method.returnType()?.let {
            writer.print("\"\n return=\"")
            writer.print(escapeAttributeValue(formatType(it)))
        }
        writer.print("\"\n abstract=\"")
        writer.print(modifiers.isAbstract())
        writer.print("\"\n native=\"")
        writer.print(modifiers.isNative())
        if (!compatibility.xmlOmitSynchronized) {
            writer.print("\"\n synchronized=\"")
            writer.print(modifiers.isSynchronized())
        }
        writer.print("\"\n static=\"")
        writer.print(modifiers.isStatic())
        writer.print("\"\n final=\"")
        writer.print(modifiers.isFinal())
        writer.print("\"\n deprecated=\"")
        writer.print(deprecation(method))
        writer.print("\"\n visibility=\"")
        writer.print(modifiers.getVisibilityModifiers())
        writer.println("\"\n>")

        writeParameterList(method)
        writeThrowsList(method)
        writer.println("</method>")
    }

    private fun writeSuperClassAttribute(cls: ClassItem) {
        if (cls.isInterface() && compatibility.extendsForInterfaceSuperClass) {
            // Written in the interface section instead
            return
        }

        val superClass = if (preFiltered)
            cls.superClassType()
        else cls.filteredSuperClassType(filterReference)

        val superClassString =
            when {
                cls.isAnnotationType() -> if (compatibility.xmlAnnotationAsObject) {
                    JAVA_LANG_OBJECT
                } else {
                    JAVA_LANG_ANNOTATION
                }
                superClass != null -> {
                    // doclava seems to include java.lang.Object for classes but not interfaces
                    if (!cls.isClass() && superClass.isJavaLangObject()) {
                        return
                    }
                    escapeAttributeValue(
                        superClass.toTypeString(
                            erased = compatibility.omitTypeParametersInInterfaces,
                            context = superClass.asClass()
                        )
                    )
                }
                cls.isEnum() -> JAVA_LANG_ENUM
                else -> return
            }
        writer.print("\n extends=\"")
        writer.print(superClassString)
        writer.print("\"")
    }

    private fun writeInterfaceList(cls: ClassItem) {
        var interfaces = if (preFiltered)
            cls.interfaceTypes().asSequence()
        else cls.filteredInterfaceTypes(filterReference).asSequence()

        if (cls.isInterface() && compatibility.extendsForInterfaceSuperClass) {
            val superClassType = cls.superClassType()
            if (superClassType?.isJavaLangObject() == false) {
                interfaces += superClassType
            }
        }

        if (interfaces.any()) {
            interfaces.sortedWith(TypeItem.comparator).forEach { item ->
                writer.print("<implements name=\"")
                val type = item.toTypeString(erased = compatibility.omitTypeParametersInInterfaces, context = cls)
                val escapedType = escapeAttributeValue(type)
                writer.print(escapedType)
                writer.println("\">\n</implements>")
            }
        }
    }

    private fun writeParameterList(method: MethodItem) {
        method.parameters().asSequence().forEach { parameter ->
            // NOTE: We report parameter name as "null" rather than the real name to match
            // doclava's behavior
            writer.print("<parameter name=\"null\" type=\"")
            writer.print(escapeAttributeValue(formatType(parameter.type())))
            writer.println("\">")
            writer.println("</parameter>")
        }
    }

    private fun formatType(type: TypeItem): String {
        val typeString = type.toTypeString()
        return if (compatibility.spaceAfterCommaInTypes) {
            typeString.replace(",", ", ").replace(",  ", ", ")
        } else {
            typeString
        }
    }

    private fun writeThrowsList(method: MethodItem) {
        val throws = when {
            preFiltered -> method.throwsTypes().asSequence()
            compatibility.filterThrowsClasses -> method.filteredThrowsTypes(filterReference).asSequence()
            else -> method.throwsTypes().asSequence()
        }
        if (throws.any()) {
            throws.asSequence().sortedWith(ClassItem.fullNameComparator).forEach { type ->
                writer.print("<exception name=\"")
                if (options.compatOutput) {
                    writer.print(type.simpleName())
                } else {
                    writer.print(type.fullName())
                }
                writer.print("\" type=\"")
                writer.print(type.qualifiedName())
                writer.println("\">")
                writer.println("</exception>")
            }
        }
    }

    private fun escapeAttributeValue(s: String): String {
        val escaped = XmlUtils.toXmlAttributeValue(s)
        return if (compatibility.xmlEscapeGreaterThan && escaped.contains(">")) {
            escaped.replace(">", "&gt;")
        } else {
            escaped
        }
    }
}