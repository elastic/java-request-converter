/*
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.example;

import co.elastic.clients.ApiClient;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.RequestBase;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch.async_search.SubmitRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.JsonEnum;
import co.elastic.clients.json.JsonpDeserializer;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.SimpleJsonpMapper;
import co.elastic.clients.util.DateTime;
import co.elastic.clients.util.NamedValue;
import co.elastic.clients.util.Pair;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.Json;
import jakarta.json.stream.JsonParser;
import org.apache.commons.text.CaseUtils;
import org.jboss.forge.roaster.Roaster;

import java.beans.Introspector;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Stack;
import java.util.StringJoiner;

public class RequestConverter {

    public static final String DEFAULT_CLIENT_NAME = "client";
    // only keeps one string per recursion depth, which is what should not be repeated
    Stack<String> alreadyUsedLambdaNames;

    static boolean complete = false;

    Set<String> imports;

    public static final String fullExampleClass = """
                import co.elastic.clients.elasticsearch.ElasticsearchClient;
                import java.io.IOException;
        
                public class App {
        
                    public static void main(String[] args) throws IOException {
                        String serverUrl = "%s";
        
                        try (
                            ElasticsearchClient client = ElasticsearchClient.of(e -> e
                                .host(serverUrl)
                                .apiKey(System.getenv("ELASTIC_API_KEY")))) {
        
                        %s
        
                        }
                    }
                }
        """;

    public String convertToDsl(String requests, boolean complete, String elasticsearchUrl) {

        // setting global variables
        this.complete = complete;
        imports = new HashSet<>();

        // parsing list of separate json requests from the common request-converter
        List<Request> requestList = getJsonData(requests);
        StringJoiner convertionResults = new StringJoiner("\n\n");

        try {
            for (Request request : requestList) {

                String apiName = request.api();
                // understand if direct call (like search) or subclient call (like ingest.put_pipeline)
                String[] apiPath = apiName.split("\\.");

                Optional<Class<?>> javaRequest;
                RequestBase instance = null;
                String clientName = DEFAULT_CLIENT_NAME;
                String methodName;
                if (apiPath.length > 1) {
                    // the first part of the api name is the subclient, the second part is the method to call
                    clientName = snakeToCamel(apiPath[0]);
                    methodName = snakeToCamel(apiPath[1]);

                    // identifying the subclient
                    Class subclient = identifySubClient(clientName);

                    // identifying the method
                    javaRequest = identifyRequest(methodName, subclient);
                } else {
                    // direct call, simply identifying the method
                    javaRequest = identifyRequest(snakeToCamel(apiName), ElasticsearchClient.class);
                    methodName = snakeToCamel(apiName);
                }

                // getting a builder instance of the class
                if (javaRequest.isPresent()) {
                    Class builder = Class.forName(javaRequest.get().getName() + "$Builder");
                    Object builderInstance = builder.getDeclaredConstructor().newInstance();

                    // fill builder with params, query params and the json body
                    // why not calling Class.of(c -> c.withJson(..)) and then fill the parameters later?
                    // because if required parameters are not in the body, request creation fails.
                    fillBuilder(builderInstance, request);

                    Method build = builder.getDeclaredMethod("build");

                    // new instance of the specific request class containing all data
                    instance = (RequestBase) build.invoke(builderInstance);
                }

                convertionResults.add(buildDslString(instance, clientName, methodName, elasticsearchUrl));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        String finalResult = convertionResults.toString();

        // must write a complete working class
        if (complete) {
            StringJoiner fullClassResult = new StringJoiner("\n\n");
            fullClassResult.add("package org.example;");
            fullClassResult.add(String.join("\n", imports));
            fullClassResult.add(String.format(fullExampleClass, elasticsearchUrl,
                convertionResults));

            finalResult = fullClassResult.toString();
        }

        return Roaster.format(finalResult);
    }

    private List<Request> getJsonData(String jsonRequests) {

        ObjectMapper mapper = new ObjectMapper();
        TypeReference<List<Request>> typeRef = new TypeReference<>() {
        };

        try {
            return mapper.readValue(jsonRequests, typeRef);
        } catch (IOException e) {
            throw new RuntimeException("Invalid input data: " + jsonRequests);
        }
    }

    private void fillBuilder(Object builder, Request request) throws NoSuchMethodException,
        InvocationTargetException, IllegalAccessException, ClassNotFoundException {
        if (request.params() != null) {
            for (Map.Entry<String, Object> entry : request.params().entrySet()) {
                try {
                    // directly assigning parameters to builder fields
                    String fieldName = snakeToCamel(entry.getKey());
                    Field field = builder.getClass().getDeclaredField(fieldName);
                    field.setAccessible(true);
                    if (field.getType().isAssignableFrom(List.class)) {
                        // check if comma separated list of values
                        String[] values = entry.getValue().toString().split(",");
                        if (values.length > 1) {
                            field.set(builder, Arrays.asList(values));
                        } else {
                            field.set(builder, List.of(entry.getValue()));
                        }
                    }
                    // case string which is actually a number
                    else if (field.getType().getSuperclass().equals(Number.class) && entry.getValue() instanceof String) {
                        Method parseMethod = field.getType().getMethod("valueOf", String.class);
                        field.set(builder, parseMethod.invoke(field, entry.getValue().toString()));
                    } else if (Arrays.stream(field.getType().getInterfaces()).anyMatch(i -> i.getSimpleName().equals(
                        "JsonEnum"))) {
                        Class<? extends Enum> en = (Class<? extends Enum>) field.getType();
                        // all enums in the java client have first capital letter and then lowercase
                        String enumValue = snakeToCamelCapitalized(entry.getValue().toString());
                        field.set(builder, Enum.valueOf((Class<? extends Enum>) Class.forName(en.getName()),
                            enumValue));
                    } else {
                        field.set(builder, entry.getValue());
                    }
                } catch (NoSuchFieldException e) {
                    // suppressing this one, could be a query/parameter that the java client does not
                    // support, like "pretty"
                }
            }
        }
        if (request.query() != null) {
            for (Map.Entry<String, Object> entry : request.query().entrySet()) {
                try {
                    // same for query params, but here there can be numbers sent as string, or unions
                    String fieldName = snakeToCamel(entry.getKey());
                    Field field = builder.getClass().getDeclaredField(fieldName);
                    field.setAccessible(true);
                    // case list
                    if (field.getType().isAssignableFrom(List.class)) {
                        // check if comma separated list of values
                        String[] values = entry.getValue().toString().split(",");
                        if (values.length > 1) {
                            field.set(builder, Arrays.asList(values));
                        } else {
                            field.set(builder, List.of(entry.getValue()));
                        }
                    }
                    // case enum
                    else if (Arrays.stream(field.getType().getInterfaces()).anyMatch(i -> i.getSimpleName().equals(
                        "JsonEnum"))) {
                        Class<? extends Enum> en = (Class<? extends Enum>) field.getType();
                        // all enums in the java client have first capital letter and then lowercase
                        String enumValue = snakeToCamelCapitalized(entry.getValue().toString());
                        field.set(builder, Enum.valueOf((Class<? extends Enum>) Class.forName(en.getName()),
                            enumValue));
                    }
                    // case string which is actually a number
                    else if (field.getType().getSuperclass().equals(Number.class) && entry.getValue() instanceof String) {
                        Method parseMethod = field.getType().getMethod("valueOf", String.class);
                        field.set(builder, parseMethod.invoke(field, entry.getValue().toString()));
                    }
                    // case string which is actually a boolean
                    else if (field.getType().equals(Boolean.class) && entry.getValue() instanceof String) {
                        Method parseMethod = field.getType().getMethod("valueOf", String.class);
                        field.set(builder, parseMethod.invoke(field, entry.getValue().toString()));
                    }
                    // case Time -> time (string with unit)
                    else if (field.getType().equals(Time.class) && entry.getValue() instanceof String) {
                        Time value = Time.of(t -> t.time(entry.getValue().toString()));
                        field.set(builder, value);
                    }
                    // case Time -> offset (int)
                    else if (field.getType().equals(Time.class) && entry.getValue() instanceof Number) {
                        Time value = Time.of(t -> t.offset((Integer) entry.getValue()));
                        field.set(builder, value);
                    }
                    // case union
                    else if (Arrays.stream(field.getType().getInterfaces()).anyMatch(i -> i.getSimpleName().equals(
                        "TaggedUnion"))) {
                        Method deserialize = Arrays.stream(field.getType().getDeclaredMethods())
                            .filter(m -> m.getName().equals("build" + field.getType().getSimpleName() +
                                                            "Deserializer"))
                            .findFirst()
                            .orElseThrow(() -> new RuntimeException("Could not find deserializer method for" +
                                                                    " " +
                                                                    "union: " + fieldName));

                        deserialize.setAccessible(true);
                        JsonpDeserializer deserializer = (JsonpDeserializer) deserialize.invoke(field);

                        Object unionValue =
                            deserializer.deserialize(Json.createParser(new StringReader((String) entry.getValue())), SimpleJsonpMapper.INSTANCE);
                        field.set(builder, unionValue);
                    }
                    // either simple string, or will throw exception
                    else {
                        field.set(builder, entry.getValue());
                    }
                } catch (NoSuchFieldException e) {
                    // suppressing this one, could be a query/parameter that the java client does not
                    // support, like "pretty"
                }
            }
        }
        if (request.body() != null && !request.body().isEmpty()) {
            try {
                Method withJson = builder.getClass().getMethod("withJson", JsonParser.class,
                    JsonpMapper.class);
                withJson.invoke(builder, Json.createParser(new StringReader(request.body().toString())),
                    SimpleJsonpMapper.INSTANCE);
            } catch (InvocationTargetException e) {
                throw new RuntimeException("Could not build request body: {" + e.getTargetException() + "}");
            }
        }
    }

    private Optional<Class<?>> identifyRequest(String name, Class client) {

        List<Method> methods = Arrays.stream(client.getMethods())
            .filter(m -> m.getName().equals(name))
            .toList();

        if (methods.isEmpty()) {
            throw new RuntimeException("No request found for name: " + name);
        }

        // if no request is found, it could be an API like "info" which has no parameter
        return methods.stream()
            .flatMap(m -> Arrays.stream(m.getParameterTypes()))
            .filter(c -> Objects.nonNull(c.getSuperclass()) && c.getSuperclass().equals(RequestBase.class))
            .findFirst();
    }

    private Class identifySubClient(String name) {
        Class<?> subClient = Arrays.stream(ElasticsearchClient.class.getMethods())
            .filter(m -> m.getName().equals(name))
            .map(Method::getReturnType)
            .filter(c -> Objects.nonNull(c.getSuperclass()) && c.getSuperclass().equals(ApiClient.class))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("No subclient found for name: " + name));

        return subClient;
    }

    // names in the spec are in snake_case while in the java client they are in camelCase
    private String snakeToCamel(String snake) {
        return CaseUtils.toCamelCase(snake, false, '_');
    }

    // for enums
    private String snakeToCamelCapitalized(String snake) {
        return CaseUtils.toCamelCase(snake, true, '_');
    }

    private String buildDslString(RequestBase request, String clientName, String methodName,
                                  String elasticsearchUrl) {
        alreadyUsedLambdaNames = new Stack<>();
        StringBuilder writer = new StringBuilder();


        // starting by calling the client
        writer.append(DEFAULT_CLIENT_NAME).append(".");

        // subclient case
        if (!Objects.equals(clientName, DEFAULT_CLIENT_NAME)) {
            writer.append(clientName).append("().");
        }
        writer.append(methodName)
            .append("(");

        // empty body
        if (request == null) {
            writer.append(");");
        } else {
            buildRecursive(request, methodName, writer, 1, false, false);
            if (request instanceof SearchRequest || request instanceof SubmitRequest || request instanceof UpdateRequest) {
                writer.append("\n,Void.class);"); //hardcoded for search and async search
            }
            // single line request, meaning empty body/params, no need to close parenthesis
            else if (!writer.toString().contains("\n")) {
                writer.append(";");
            } else {
                writer.append("\n);");
            }
        }

        // avoiding double newlines (can happen when union of unions happen)
        return writer.toString().replace("\n\n", "\n");

    }

    // recursive method that builds the dsl request by going though all the request fields and subcomponents
    // object: initially the request, then all of its components
    // name: the current field name, will be used to call the constructor and to name the lambda expressions
    // writer: gradually building the dsl request
    // depth: current recursion depth, used for indentation
    // inListOrMap: the indentation style changes whether the builder is inside a List.of or Map.of
    private void buildRecursive(Object object, String name, StringBuilder writer, int depth,
                                boolean inListOrMap, boolean cannotSingleElement) {
        try {
            // "leaf" case for simple data types
            if (handleDataTypes(object, name, writer, depth, inListOrMap, cannotSingleElement)) return;
            generateLambdaCall(writer, name);

            // if class is union, handling its value directly
            if (retrieveAllFields(object).stream().anyMatch(x -> x.getName().contains("_value"))) {
                Field unionValue = object.getClass().getDeclaredField("_value");
                unionValue.setAccessible(true);

                if (unionValue.get(object) != null) {
                    // actually handling the union value
                    handleUnion(writer, object, depth, inListOrMap);
                    // unions can inherit fields outside its value
                    boolean nofields = handleAllFields(object, writer, depth);
                    // to avoid newlines for empty classes like matchAll(m -> m)
                    if (!nofields) {
                        writer.append("\n");
                    }
                    // depth-1 because unions have 2 levels, now closing the main one
                    indent(writer, depth - 1);
                    writer.append(")");
                    return;
                }
                // special case like FunctionScore that accepts no variant
                else {
                    writer.append("\n");
                }
            }
            // handling all the fields in the class
            boolean nofields = handleAllFields(object, writer, depth);
            if (!inListOrMap) {
                // to avoid newlines for empty classes like matchAll(m -> m)
                if (nofields) {
                    writer.append(")");
                } else {
                    // avoid closing bracket if it's the final line
                    // correct final closure handled in buildDslString depending on request class
                    if (depth > 1) {
                        writer.append("\n");
                        // closing bracket after handling all fields of a standard class
                        // like when exiting a union, depth - 1 to close the last level
                        indent(writer, depth - 1);
                        writer.append(")");
                    }
                }
            }
            // going up one level of depth, lambda name is again usable
            alreadyUsedLambdaNames.pop();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // loops through every field of a class to analyze them recursively
    private boolean handleAllFields(Object object, StringBuilder writer, int depth) throws IllegalAccessException {
        List<Field> allFields = retrieveAllFields(object);
        // classes with no fields need special indenting
        boolean nofields = true;
        //manually exclude DESERIALIZER, ENDPOINT, serialVersionUID, mapper, isExpNull
        List<Field> fields =
            allFields.stream().filter(x -> !x.getName().contains("_") && !x.getName().contains(
                "serialVersionUID") && !x.getName().contains(
                "mapper") && !x.getName().contains("isExpNull")).toList();

        for (Field field : fields) {
            field.setAccessible(true);
            Object fieldValue = field.get(object);

            if (fieldValue != null && notEmpty(fieldValue)) {
                nofields = false;
                writer.append("\n");
                indent(writer, depth);
                writer.append(".")
                    .append(field.getName())
                    .append("(");

                // repeating process with the field value
                buildRecursive(fieldValue, field.getName(), writer, depth + 1, false, false);
            }
        }
        return nofields;
    }

    // indents based on current depth
    private static void indent(StringBuilder writer, int depth) {
        // basic indent if writing the complete class
        if (complete) writer.append("            ");
        for (int i = 0; i < depth; i++) {
            writer.append("    ");
        }
    }

    private static List<Field> retrieveAllFields(Object object) {
        return getAllFields(new ArrayList<>(), object.getClass());
    }

    // recursively getting all fields from parent classes
    public static List<Field> getAllFields(List<Field> fields, Class<?> type) {
        fields.addAll(Arrays.asList(type.getDeclaredFields()));

        if (type.getSuperclass() != null) {
            getAllFields(fields, type.getSuperclass());
        }

        return fields;
    }

    // specific builder for elements inside a list or map
    private void buildFromRequest(Object object, String name, StringBuilder writer, int depth) {
        if (handleDataTypes(object, name, writer, depth, true, false)) return;
        {
            if (complete) {
                imports.add("import " + object.getClass().getName() + ";");
            }
            // Build element like Query.of(..)
            String className = object.getClass().getSimpleName();
            // TODO THIS SHOULD BE FIXED IN THE JAVA CLIENT
            if (className.equals("QueryRule")) {
                writer.append(className)
                    .append(".queryRuleOf(");
            } else {
                writer.append(className)
                    .append(".of(");
            }
            buildRecursive(object, Introspector.decapitalize(className), writer, depth + 1, false, false);
        }
    }

    private boolean handleDataTypes(Object object, String name, StringBuilder writer, int depth,
                                    boolean inListOrMap, boolean cannotSingleElement) {
        if (object instanceof FieldValue) {
            handleFieldValue(object, writer, inListOrMap);
            return true;
        }
        if (object instanceof NamedValue) {
            handleNamedValue(object, writer, depth, inListOrMap);
            return true;
        }
        if (object instanceof Pair) {
            handlePair(object, writer, depth, inListOrMap);
            return true;
        }
        if (object instanceof DateTime) {
            handleDateTime(object, writer, depth, inListOrMap);
            return true;
        }
        if (object instanceof JsonData) {
            handleJsonData(object, writer, inListOrMap);
            return true;
        }
        if (object instanceof Boolean || object instanceof Number) {
            handlePrimitive(object, writer, inListOrMap);
            return true;
        }
        if (object instanceof String) {
            handleString(object, writer, inListOrMap);
            return true;
        }
        if (object instanceof JsonEnum) {
            handleEnumValue(object, writer, inListOrMap);
            return true;
        }
        if (object instanceof List) {
            handleList(writer, object, name, depth, inListOrMap, cannotSingleElement);
            return true;
        }
        if (object instanceof Map) {
            handleMap(writer, object, name, depth, inListOrMap, cannotSingleElement);
            return true;
        }
        return false;
    }

    // generates lambda expressions making sure there are no duplicates
    private void generateLambdaCall(StringBuilder writer, String name) {
        int letters = 1;
        // failsafe in case not enough letters, numbers will be added to name
        int extraLetters = 1;
        String sub = name.substring(0, 1);
        String originalName = name;

        while (alreadyUsedLambdaNames.contains(sub)) {
            if (letters + 1 > originalName.length()) {
                originalName = name.concat(String.valueOf(extraLetters));
                extraLetters++;
            } else {
                letters++;
            }
            sub = originalName.substring(0, letters);
        }

        writer.append(sub)
            .append(" -> ")
            .append(sub);

        alreadyUsedLambdaNames.push(sub);
    }

    private void handlePrimitive(Object prim, StringBuilder writer, boolean inListOrMap) {
        writer.append(prim);
        if (prim instanceof Float) {
            writer.append("F");
        } else if (prim instanceof Double) {
            writer.append("D");
        } else if (prim instanceof Long) {
            writer.append("L");
        }
        if (!inListOrMap) {
            writer.append(")");
        }
    }

    private void handleString(Object string, StringBuilder writer, boolean inListOrMap) {
        writer.append("\"")
            .append(string)
            .append("\"");
        if (!inListOrMap) {
            writer.append(")");
        }
    }

    // making whatever is in the value compliant with json by escaping all quotes
    private void handleJsonData(Object json, StringBuilder writer, boolean inListOrMap) {
        if (complete) {
            imports.add("import co.elastic.clients.json.JsonData;");
        }
        writer.append("JsonData.fromJson(")
            .append("\"")
            .append(json.toString().replaceAll("\"", "\\\\\""))
            .append("\"").append(")");
        if (!inListOrMap) {
            writer.append(")");
        }
    }

    // creates new enum from the value, like Operator.And
    private void handleEnumValue(Object enumValue, StringBuilder writer, boolean inListOrMap) {
        if (complete) {
            imports.add("import " + enumValue.getClass().getName() + ";");
        }
        String classname = enumValue.getClass().getSimpleName();
        writer.append(classname)
            .append(".")
            .append(enumValue);
        if (!inListOrMap) {
            writer.append(")");
        }
    }

    // uses the most common way to create a new FieldValue, FieldValue.of(value)
    private void handleFieldValue(Object fieldValue, StringBuilder writer, boolean inListOrMap) {
        if (complete) {
            imports.add("import " + fieldValue.getClass().getName() + ";");
        }
        try {
            Method get = fieldValue.getClass().getMethod("_get");
            get.setAccessible(true);
            Object result = get.invoke(fieldValue);
            if (result == null) {
                writer.append(result);
            } else {
                writer.append("FieldValue.of(");
                if (result instanceof String) {
                    writer.append("\"")
                        .append(result)
                        .append("\"");
                } else {
                    writer.append(result);
                }
                writer.append(")");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create FieldValue from value: " + fieldValue + " error: " + e.getMessage());
        }
        if (!inListOrMap) {
            writer.append(")");
        }
    }

    // uses the most common way to create a new NamedValue, NamedValue.of("key",object)
    private void handleNamedValue(Object fieldValue, StringBuilder writer, int depth, boolean inListOrMap) {
        if (complete) {
            imports.add("import " + fieldValue.getClass().getName() + ";");
        }
        try {
            Field name = fieldValue.getClass().getDeclaredField("name");
            name.setAccessible(true);
            String nameValue = (String) name.get(fieldValue);

            writer.append("NamedValue.of(")
                .append("\"")
                .append(nameValue)
                .append("\"")
                .append(",");

            Field object = fieldValue.getClass().getDeclaredField("value");
            object.setAccessible(true);
            Object objectValue = object.get(fieldValue);

            handleDataTypes(objectValue, nameValue, writer, depth, inListOrMap, true);

        } catch (Exception e) {
            throw new RuntimeException("Failed to create NamedValue from value: " + fieldValue + " error: " + e.getMessage());
        }
        if (!inListOrMap) {
            writer.append(")");
        }
    }

    // similar to namedValue, but key can be an object (usually enum)
    private void handlePair(Object fieldValue, StringBuilder writer, int depth, boolean inListOrMap) {
        if (complete) {
            imports.add("import " + fieldValue.getClass().getName() + ";");
        }
        try {
            Field name = fieldValue.getClass().getDeclaredField("name");
            name.setAccessible(true);
            Object nameValue = name.get(fieldValue);


            writer.append("Pair.of(");

            // not really in a list or map, but no need for the brackets in a Pair either
            handleDataTypes(nameValue, "", writer, depth, true, false);

            writer.append(",");

            Field object = fieldValue.getClass().getDeclaredField("value");
            object.setAccessible(true);
            Object objectValue = object.get(fieldValue);

            handleDataTypes(objectValue, name.getName(), writer, depth, inListOrMap, false);

        } catch (Exception e) {
            throw new RuntimeException("Failed to create Pair from value: " + fieldValue + " error: " + e.getMessage());
        }
        if (!inListOrMap) {
            writer.append(")");
        }
    }

    private void handleDateTime(Object fieldValue, StringBuilder writer, int depth, boolean inListOrMap) {
        if (complete) {
            imports.add("import " + fieldValue.getClass().getName() + ";");
        }
        try {
            Field name = fieldValue.getClass().getDeclaredField("str");
            name.setAccessible(true);
            Object nameValue = name.get(fieldValue);

            if (nameValue != null) {
                writer.append("DateTime.of(");
            } else {
                name = fieldValue.getClass().getDeclaredField("millis");
                name.setAccessible(true);
                nameValue = name.get(fieldValue);
                if (nameValue != null) {
                    writer.append("DateTime.ofEpochMilli(");
                }
            }
            handleDataTypes(nameValue, "", writer, depth, inListOrMap, false);

            // TODO handle DateTime constructor with more than one arg
//            writer.append(",");
//
//            Field object = fieldValue.getClass().getDeclaredField("value");
//            object.setAccessible(true);
//            Object objectValue = object.get(fieldValue);
//
//            handleDataTypes(objectValue, name.getName(), writer, depth, inListOrMap);

        } catch (Exception e) {
            throw new RuntimeException("Failed to create DateTime from str: " + fieldValue + " error: " + e.getMessage());
        }
        if (!inListOrMap) {
            writer.append(")");
        }
    }

    // if a list has only a single element it's always possible to replace it with an instance of the element
    private void handleList(StringBuilder writer, Object list, String name, int depth, boolean inListOrMap,
                            boolean cannotSingleElement) {
        // if class is an array or a list, find out if just one or multi
        if (((List) list).size() > 1 || cannotSingleElement) {
            if (complete) {
                imports.add("import java.util.List;");
            }
            // separate string builder to handle comma separated java 9 List.of
            StringJoiner listJoiner = new StringJoiner(",", "List.of(", ")");
            for (Object element : ((List) list)) {
                StringBuilder listBuilder = new StringBuilder();
                buildFromRequest(element, name, listBuilder, depth);
                listJoiner.add(listBuilder.toString());
            }
            writer.append(listJoiner);
            writer.append(")");
        }
        // need to extrapolate single element
        else {
            buildRecursive(((List) list).get(0), name, writer, depth, inListOrMap, true);
        }
    }

    // if a map has only a single element it's always possible to replace it with its key
    //  and the lambda expression to build the single element
    private void handleMap(StringBuilder writer, Object map, String name, int depth, boolean inListOrMap,
                           boolean cannotSingleElement) {
        if (((Map) map).size() > 1 || cannotSingleElement) {
            if (complete) {
                imports.add("import java.util.Map;");
            }
            // separate string builder to handle comma separated java 9 Map.of
            StringJoiner listJoiner = new StringJoiner(",", "Map.of(", ")");
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) map).entrySet()) {
                StringBuilder listBuilder = new StringBuilder();
                listBuilder.append("\"");
                listBuilder.append(entry.getKey());
                listBuilder.append("\", ");
                buildFromRequest(entry.getValue(), name, listBuilder, depth);
                listJoiner.add(listBuilder.toString());
            }
            writer.append(listJoiner);
            if (!inListOrMap) {
                writer.append(")");
            }
        }
        // need to extrapolate single element
        else {
            writer.append("\"")
                .append(((Map<?, ?>) map).keySet().iterator().next())
                .append("\", ");
            buildRecursive(((Map<?, ?>) map).entrySet().iterator().next().getValue(), name,
                writer,
                depth, inListOrMap, true);
        }
    }

    // unions are treated like wrappers for the specific inner type
    private void handleUnion(StringBuilder writer, Object union, int depth, boolean inListOrMap) throws NoSuchFieldException,
        IllegalAccessException {

        Field value = union.getClass().getDeclaredField("_value");
        Field kind = union.getClass().getDeclaredField("_kind");
        value.setAccessible(true);
        kind.setAccessible(true);

        writer.append("\n");
        indent(writer, depth);
        writer.append(".");
        String decap = Introspector.decapitalize(kind.get(union).toString());
        writer.append(decap);
        writer.append("(");

        buildRecursive(value.get(union), decap, writer, depth + 1, inListOrMap, false);
        writer.append("\n");
    }

    // if maps or lists are empty field can be ignored
    private boolean notEmpty(Object o) {
        if (o instanceof Map) {
            return !((Map<?, ?>) o).isEmpty();
        }
        if (o instanceof List) {
            return !((List<?>) o).isEmpty();
        }
        // it wasn't a list or map
        return true;
    }
}
