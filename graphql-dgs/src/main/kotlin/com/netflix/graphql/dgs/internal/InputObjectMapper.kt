/*
 * Copyright 2021 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.graphql.dgs.internal

import com.fasterxml.jackson.module.kotlin.isKotlinClass
import com.netflix.graphql.dgs.exceptions.DgsInvalidInputArgumentException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.util.ReflectionUtils
import java.lang.reflect.Field
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.jvmErasure

@Suppress("UNCHECKED_CAST")
object InputObjectMapper {
    val logger: Logger = LoggerFactory.getLogger(InputObjectMapper::class.java)

    fun <T : Any> mapToKotlinObject(inputMap: Map<String, *>, targetClass: KClass<T>): T {
        val params = targetClass.primaryConstructor!!.parameters
        val inputValues = mutableListOf<Any?>()

        params.forEach { parameter ->
            val input = inputMap[parameter.name]
            if (input is Map<*, *>) {
                val nestedTarget = parameter.type.jvmErasure
                val subValue = if (isObjectOrAny(nestedTarget)) {
                    input
                } else if (nestedTarget.java.isKotlinClass()) {
                    mapToKotlinObject(input as Map<String, *>, nestedTarget)
                } else {
                    mapToJavaObject(input as Map<String, *>, nestedTarget.java)
                }
                inputValues.add(subValue)
            } else if (parameter.type.jvmErasure.java.isEnum && input !== null) {
                val enumValue = (parameter.type.jvmErasure.java.enumConstants as Array<Enum<*>>).find { enumValue -> enumValue.name == input }
                inputValues.add(enumValue)
            } else if (input is List<*>) {
                val newList = convertList(input, parameter.type.arguments[0].type!!.jvmErasure)
                inputValues.add(newList)
            } else {
                inputValues.add(input)
            }
        }

        return targetClass.primaryConstructor!!.call(*inputValues.toTypedArray())
    }

    fun <T> mapToJavaObject(inputMap: Map<String, *>, targetClass: Class<T>): T {
        if (targetClass == Object::class.java) {
            return inputMap as T
        }

        val ctor = targetClass.getDeclaredConstructor()
        ctor.isAccessible = true
        val instance = ctor.newInstance()
        var nrOfFieldErrors = 0
        inputMap.forEach {
            val declaredField = ReflectionUtils.findField(targetClass, it.key)
            if (declaredField != null) {
                declaredField.isAccessible = true
                val actualType = getFieldType(declaredField, targetClass.genericSuperclass)

                if (it.value is Map<*, *>) {
                    val mappedValue = if (actualType.isKotlinClass()) {
                        mapToKotlinObject(it.value as Map<String, *>, actualType.kotlin)
                    } else {
                        mapToJavaObject(it.value as Map<String, *>, actualType)
                    }

                    declaredField.set(instance, mappedValue)
                } else if (it.value is List<*>) {
                    val newList = convertList(it.value as List<*>, Class.forName(actualType.typeName).kotlin)
                    declaredField.set(instance, newList)
                } else if (actualType.isEnum) {
                    val enumValue = (actualType.enumConstants as Array<Enum<*>>).find { enumValue -> enumValue.name == it.value }
                    declaredField.set(instance, enumValue)
                } else {
                    declaredField.set(instance, it.value)
                }
            } else {
                logger.warn("Field '${it.key}' was not found on Input object of type '$targetClass'")
                nrOfFieldErrors++
            }
        }

        /**
         We can't error out if only some fields don't match.
         This would happen if new schema fields are added, but the Java type wasn't updated yet.
         If none of the fields match however, it's a pretty good indication that the wrong type was used, hence this check.
         */
        if (nrOfFieldErrors == inputMap.size) {
            throw DgsInvalidInputArgumentException("Input argument type '$targetClass' doesn't match input $inputMap")
        }

        return instance
    }

    fun getFieldType(field: Field, genericSuperclass: Type,): Class<*> {
        val type: Type = field.genericType
        return if (type is ParameterizedType) {
            Class.forName(type.actualTypeArguments[0].typeName)
        } else if (genericSuperclass is ParameterizedType && field.type != field.genericType) {
            val typeParameters = (genericSuperclass.rawType as Class<*>).typeParameters
            val indexOfTypeParameter = typeParameters.indexOfFirst { it.name == type.typeName }
            Class.forName(genericSuperclass.actualTypeArguments[indexOfTypeParameter].typeName)
        } else {
            field.type
        }
    }

    private fun convertList(input: List<*>, nestedTarget: KClass<*>): List<*> {
        return input.filterNotNull().map { listItem ->
            if (nestedTarget.java.isAssignableFrom(listItem::class.java)) {
                listItem
            } else if (nestedTarget.java.isEnum) {
                (nestedTarget.java.enumConstants as Array<Enum<*>>).first { it.name == listItem }
            } else if (listItem is Map<*, *>) {
                if (isObjectOrAny(nestedTarget)) {
                    listItem
                } else if (nestedTarget.java.isKotlinClass()) {
                    mapToKotlinObject(listItem as Map<String, *>, nestedTarget)
                } else {
                    mapToJavaObject(listItem as Map<String, *>, nestedTarget.java)
                }
            } else {
                listItem
            }
        }
    }

    private fun isObjectOrAny(nestedTarget: KClass<*>) =
        nestedTarget.java == Object::class.java || nestedTarget == Any::class
}