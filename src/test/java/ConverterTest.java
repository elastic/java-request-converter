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

import org.example.RequestConverter;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.io.IOException;

public class ConverterTest {

    @Test
    public void skippedFieldPlusEnumParam() {
        // TODO handle enums in params
        String req = "[{\"api\":\"indices.get_settings\",\"query\":{\"expand_wildcards\":\"all\", " +
                     "\"filter_path\":\"*.settings.index.*.slowlog\"},\"body\":{}}]";
        RequestConverter requestConverter = new RequestConverter();
        String response = requestConverter.convertToDsl(req, false, "http://localhost:9200/");
        String expected = """
            client.indices().getSettings(g -> g
                .expandWildcards("all")
            );
            """;
        Assert.assertEquals(expected.trim(), response);
    }

    //@Test
    public void ndjsonNotSupported() {
        // STILL BROKEN. it's ndjson, java client currently does not support ndjson deserialization.
        String req = "[{\"api\":\"msearch\",\"params\":{\"index\":\"my-index-000001\"},\"body\":[{}," +
                     "{\"query\":{\"match\":{\"message\":\"this is a test\"}}}," +
                     "{\"index\":\"my-index-000002\"},{\"query\":{\"match_all\":{}}}]}]\n";
        RequestConverter requestConverter = new RequestConverter();
        String response = requestConverter.convertToDsl(req, false, "http://localhost:9200/");
        System.out.println(response);
    }

    @Test
    public void paramList() {
        String req = "        [{\"api\":\"cluster.reroute\",\"params\":{},\"query\":{\"metric\":\"none\"}," +
                     "\"body\":{\"commands\":[{\"move\":{\"index\":\"test\",\"shard\":0," +
                     "\"from_node\":\"node1\",\"to_node\":\"node2\"}}," +
                     "{\"allocate_replica\":{\"index\":\"test\",\"shard\":1,\"node\":\"node3\"}}]}}]\n";
        RequestConverter requestConverter = new RequestConverter();
        String response = requestConverter.convertToDsl(req, false, "http://localhost:9200/");
        String expected = """
            client.cluster().reroute(r -> r
                .commands(List.of(Command.of(c -> c
                        .move(m -> m
                            .index("test")
                            .shard(0)
                            .fromNode("node1")
                            .toNode("node2")
                        )
                    ),Command.of(c -> c
                        .allocateReplica(a -> a
                            .index("test")
                            .shard(1)
                            .node("node3")
                        )
                    )))
                .metric("none")
            );
            """;
        Assert.assertEquals(expected.trim(), response);
    }

    @Test
    public void paramNumber() {
        String req = "[{\"api\":\"ml.put_trained_model_definition_part\"," +
                     "\"params\":{\"model_id\":\"elastic__distilbert-base-uncased-finetuned-conll03-english" +
                     "\",\"part\":\"0\"},\"body\":{\"definition\":\"...\"," +
                     "\"total_definition_length\":265632637,\"total_parts\":64}}]\n";
        RequestConverter requestConverter = new RequestConverter();
        String response = requestConverter.convertToDsl(req, false, "http://localhost:9200/");
        String expected = """
            client.ml().putTrainedModelDefinitionPart(p -> p
                .definition("...")
                .modelId("elastic__distilbert-base-uncased-finetuned-conll03-english")
                .part(0)
                .totalDefinitionLength(265632637L)
                .totalParts(64)
            );
            """;
        Assert.assertEquals(expected.trim(), response);
    }

    @Test
    public void singleItemMap() {
        String inferTrainedModel = """
                        [
                {
                    "params": {
                        "model_id": "test"
                    },
                    "query": {},
                    "body":   {
                                            "docs":[{"text": "The fool doth think he is wise, but the wise man knows himself to be a fool."}]
                                          },
                    "api": "ml.infer_trained_model"
                }
            ]
            """;
        RequestConverter requestConverter = new RequestConverter();
        String response = requestConverter.convertToDsl(inferTrainedModel, false, "http://localhost:9200/");
        String expected = """
            client.ml().inferTrainedModel(i -> i
                .docs(Map.of("text", JsonData.fromJson("\\"The fool doth think he is wise, but the wise man knows himself to be a fool.\\"")))
                .modelId("test")
            );
            """;
        Assert.assertEquals(expected.trim(), response);
    }


    @Test
    public void nestedListsCase() throws IOException {
        String securityPutTest = """
            [
                {
                    "params": {
                        "name": "mapping8"
                    },
                    "query": {},
                    "body":   {
                                     "roles": [ "superuser" ],
                                     "enabled": true,
                                     "rules": {
                                       "all": [
                                         {
                                           "any": [
                                             {
                                               "field": {
                                                 "dn": "*,ou=admin,dc=example,dc=com"
                                               }
                                             },
                                             {
                                               "field": {
                                                 "username": [ "es-admin", "es-system" ]
                                               }
                                             }
                                           ]
                                         },
                                         {
                                           "field": {
                                             "groups": "cn=people,dc=example,dc=com"
                                           }
                                         },
                                         {
                                           "except": {
                                             "field": {
                                               "metadata.terminated_date": null
                                             }
                                           }
                                         }
                                       ]
                                     }
                                   },
                    "api": "security.put_role_mapping"
                }
            ]
            """;

        RequestConverter requestConverter = new RequestConverter();
        String response = requestConverter.convertToDsl(securityPutTest, false, "http://localhost:9200/");
        String expected = """
            client.security().putRoleMapping(p -> p
                .enabled(true)
                .name("mapping8")
                .roles("superuser")
                .rules(r -> r
                    .all(List.of(RoleMappingRule.of(ro -> ro
                            .any(List.of(RoleMappingRule.of(rol -> rol
                                    .field(NamedValue.of("dn",List.of(FieldValue.of("*,ou=admin,dc=example,dc=com"))))
                                ),RoleMappingRule.of(role -> role
                                    .field(NamedValue.of("username",List.of(FieldValue.of("es-admin"),FieldValue.of("es-system"))))
                                )))
                        ),RoleMappingRule.of(roleM -> roleM
                            .field(NamedValue.of("groups",List.of(FieldValue.of("cn=people,dc=example,dc=com"))))
                        ),RoleMappingRule.of(roleMa -> roleMa
                            .except(e -> e
                                .field(NamedValue.of("metadata.terminated_date",List.of(null)))
                            )
                        )))
                )
            );
            """;
        Assert.assertEquals(expected.trim(), response);
    }

    @Test
    public void convertMultipleInFullClass() {
        String simpleRequests = "[{\"api\":\"info\",\"params\":{}},{\"api\":\"search\"," +
                                "\"params\":{\"index\":\"my-index\"},\"query\":{\"from\":\"40\"," +
                                "\"size\":\"20\"},\"body\":{\"query\":{\"term\":{\"user" +
                                ".id\":\"kimchy's\"}}}}]";
        RequestConverter requestConverter = new RequestConverter();
        String response = requestConverter.convertToDsl(simpleRequests, true, "http://localhost:9200/");
        String expected = """
            package org.example;
            
            import co.elastic.clients.elasticsearch._types.FieldValue;
            
            import co.elastic.clients.elasticsearch.ElasticsearchClient;
            import java.io.IOException;
            
            public class App {
            
            	public static void main(String[] args) throws IOException {
            		String serverUrl = "http://localhost:9200/";
            
            		try (ElasticsearchClient client = ElasticsearchClient
            				.of(e -> e.host(serverUrl).apiKey(System.getenv("ELASTIC_API_KEY")))) {
            
            			client.info();
            
            			client.search(
            					s -> s.from(40).index("my-index")
            							.query(q -> q.term(t -> t.field("user.id").value(FieldValue.of("kimchy's")))).size(20),
            					Void.class);
            
            		}
            	}
            }
            """;
        Assert.assertEquals(expected, response);
    }

    @Test
    public void simpleMatchAll() {
        String simpleMatchAll = "[\n" +
                                "  {\n" +
                                "    \"params\": { \"index\": \"my-index-000001,my-index-000002\" },\n" +
                                "    \"query\": { \"from\": 40, \"size\": 20, \"default_operator\": \"AND\"" +
                                " },\n" +
                                "    \"body\": {\n" +
                                "    \"query\": {\n" +
                                "        \"match_all\": {}\n" +
                                "    }\n" +
                                "}" +
                                ",\n" +
                                "    \"api\": \"search\"\n" +
                                "  }\n" +
                                "]";
        RequestConverter requestConverter = new RequestConverter();
        String response = requestConverter.convertToDsl(simpleMatchAll, false, "http://localhost:9200/");
        String expected = """
            client.search(s -> s
                .defaultOperator(Operator.And)
                .from(40)
                .index(List.of("my-index-000001","my-index-000002"))
                .query(q -> q
                    .matchAll(m -> m)
                )
                .size(20)
            ,Void.class);
            """;
        Assert.assertEquals(expected.trim(), response);
    }

    @Test
    public void rangeQuery() {
        String rangeQuery = "[\n" +
                            "  {\n" +
                            "    \"params\": { \"index\": \"my-index-000001\" },\n" +
                            "    \"query\": { \"from\": 40, \"size\": 20 },\n" +
                            "    \"body\": {\n" +
                            "  \"query\": {\n" +
                            "    \"range\": {\n" +
                            "      \"@timestamp\": {\n" +
                            "        \"gte\": \"now-1d/d\",\n" +
                            "        \"lt\": \"now/d\"\n" +
                            "      }\n" +
                            "    }\n" +
                            "  },\n" +
                            "  \"aggs\": {\n" +
                            "    \"my-agg-name\": {\n" +
                            "      \"terms\": {\n" +
                            "        \"field\": \"my-field\"\n" +
                            "      }\n" +
                            "    }\n" +
                            "  }\n" +
                            "}" +
                            ",\n" +
                            "    \"api\": \"search\"\n" +
                            "  }\n" +
                            "]";
        RequestConverter requestConverter = new RequestConverter();
        String response = requestConverter.convertToDsl(rangeQuery, false, "http://localhost:9200/");
        String expected = """
            client.search(s -> s
                .aggregations("my-agg-name", a -> a
                    .terms(t -> t
                        .field("my-field")
                    )
                )
                .from(40)
                .index("my-index-000001")
                .query(q -> q
                    .range(r -> r
                        .untyped(u -> u
                            .field("@timestamp")
                            .gte(JsonData.fromJson("\\"now-1d/d\\""))
                            .lt(JsonData.fromJson("\\"now/d\\""))
                        )
                    )
                )
                .size(20)
            ,Void.class);
            """;
        Assert.assertEquals(expected.trim(), response);
    }

    @Test
    public void nestedAgg() {
        String nestedAgg = "[\n" +
                           "  {\n" +
                           "    \"params\": {},\n" +
                           "    \"query\": {},\n" +
                           "    \"body\": {\n" +
                           "  \"aggs\": {\n" +
                           "    \"my-agg-name\": {\n" +
                           "      \"terms\": {\n" +
                           "        \"field\": \"my-field\"\n" +
                           "      },\n" +
                           "      \"aggs\": {\n" +
                           "        \"my-sub-agg-name\": {\n" +
                           "          \"avg\": {\n" +
                           "            \"field\": \"my-other-field\"\n" +
                           "          }\n" +
                           "        }\n" +
                           "      }\n" +
                           "    }\n" +
                           "  }\n" +
                           "}" +
                           ",\n" +
                           "    \"api\": \"search\"\n" +
                           "  }\n" +
                           "]";
        RequestConverter requestConverter = new RequestConverter();
        String response = requestConverter.convertToDsl(nestedAgg, false, "http://localhost:9200/");
        String expected = """
            client.search(s -> s
                .aggregations("my-agg-name", a -> a
                    .terms(t -> t
                        .field("my-field")
                    )
                    .aggregations("my-sub-agg-name", ag -> ag
                        .avg(av -> av
                            .field("my-other-field")
                        )
                    )
                )
            ,Void.class);
            """;
        Assert.assertEquals(expected.trim(), response);
    }

    @Test
    public void aggWithMetadata() {
        String aggWithMetadata = "[\n" +
                                 "  {\n" +
                                 "    \"params\": {},\n" +
                                 "    \"query\": {},\n" +
                                 "    \"body\": {\n" +
                                 "  \"aggs\": {\n" +
                                 "    \"my-agg-name\": {\n" +
                                 "      \"terms\": {\n" +
                                 "        \"field\": \"my-field\"\n" +
                                 "      },\n" +
                                 "      \"meta\": {\n" +
                                 "        \"my-metadata-field\": \"foo\"\n" +
                                 "      }\n" +
                                 "    }\n" +
                                 "  }\n" +
                                 "}" +
                                 ",\n" +
                                 "    \"api\": \"search\"\n" +
                                 "  }\n" +
                                 "]";
        RequestConverter requestConverter = new RequestConverter();
        String response = requestConverter.convertToDsl(aggWithMetadata, false, "http://localhost:9200/");
        String expected = """
            client.search(s -> s
                .aggregations("my-agg-name", a -> a
                    .terms(t -> t
                        .field("my-field")
                    )
                    .meta("my-metadata-field", JsonData.fromJson("\\"foo\\""))
                )
            ,Void.class);
            """;
        Assert.assertEquals(expected.trim(), response);
    }

    @Test
    public void script() {
        String script = "[\n" +
                        "  {\n" +
                        "    \"params\": {},\n" +
                        "    \"query\": {},\n" +
                        "    \"body\": {\n" +
                        "  \"runtime_mappings\": {\n" +
                        "    \"message.length\": {\n" +
                        "      \"type\": \"long\",\n" +
                        "      \"script\": \"emit(doc['message.keyword'].value.length())\"\n" +
                        "    }\n" +
                        "  },\n" +
                        "  \"aggs\": {\n" +
                        "    \"message_length\": {\n" +
                        "      \"histogram\": {\n" +
                        "        \"interval\": 10,\n" +
                        "        \"field\": \"message.length\"\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "}" +
                        ",\n" +
                        "    \"api\": \"search\"\n" +
                        "  }\n" +
                        "]";
        RequestConverter requestConverter = new RequestConverter();
        String response = requestConverter.convertToDsl(script, false, "http://localhost:9200/");
        String expected = """
            client.search(s -> s
                .aggregations("message_length", a -> a
                    .histogram(h -> h
                        .field("message.length")
                        .interval(10.0D)
                    )
                )
                .runtimeMappings("message.length", r -> r
                    .script(sc -> sc
                        .source(so -> so
                            .scriptString("emit(doc['message.keyword'].value.length())")
                        )
                    )
                    .type(RuntimeFieldType.Long)
                )
            ,Void.class);
            """;
        Assert.assertEquals(expected.trim(), response);
    }


    @Test
    public void functionScore() {
        String functionScore = "[\n" +
                               "  {\n" +
                               "    \"params\": {},\n" +
                               "    \"query\": {},\n" +
                               "    \"body\": {\n" +
                               "  \"size\": 10,\n" +
                               "  \"query\": {\n" +
                               "    \"function_score\": {\n" +
                               "      \"query\": {\n" +
                               "        \"bool\": {\n" +
                               "          \"filter\": [\n" +
                               "            {\n" +
                               "              \"terms\": {\n" +
                               "                \"tags.keyword\": [\"Monkey\", \"Lion\"]\n" +
                               "              }\n" +
                               "            }\n" +
                               "          ]\n" +
                               "        }\n" +
                               "      },\n" +
                               "      \"functions\": [\n" +
                               "        {\n" +
                               "          \"filter\": {\n" +
                               "            \"term\": {\n" +
                               "              \"mustHaveTags.keyword\": {\"value\": \"Monkey\"}\n" +
                               "            }\n" +
                               "          },\n" +
                               "          \"weight\": 1\n" +
                               "        }],\n" +
                               "      \"score_mode\": \"sum\",\n" +
                               "      \"boost_mode\": \"sum\"\n" +
                               "    }\n" +
                               "  }\n" +
                               "}" +
                               ",\n" +
                               "    \"api\": \"search\"\n" +
                               "  }\n" +
                               "]";
        RequestConverter requestConverter = new RequestConverter();
        String response = requestConverter.convertToDsl(functionScore, false, "http://localhost:9200/");
        String expected = """
            client.search(s -> s
                .query(q -> q
                    .functionScore(f -> f
                        .boostMode(FunctionBoostMode.Sum)
                        .functions(fu -> fu
                            .filter(fi -> fi
                                .term(t -> t
                                    .field("mustHaveTags.keyword")
                                    .value(FieldValue.of("Monkey"))
                                )
                            )
                            .weight(1.0D)
                        )
                        .query(qu -> qu
                            .bool(b -> b
                                .filter(fi -> fi
                                    .terms(t -> t
                                        .field("tags.keyword")
                                        .terms(te -> te
                                            .value(List.of(FieldValue.of("Monkey"),FieldValue.of("Lion")))
                                        )
                                    )
                                )
                            )
                        )
                        .scoreMode(FunctionScoreMode.Sum)
                    )
                )
                .size(10)
            ,Void.class);
            """;
        Assert.assertEquals(expected.trim(), response);
    }

    @Test
    public void pipeline() {
        String pipeline = "[\n" +
                          "  {\n" +
                          "    \"params\": { \"id\": \"my-pipeline\" },\n" +
                          "    \"body\": {\n" +
                          "      \"description\": \"My optional pipeline description\",\n" +
                          "      \"processors\": [\n" +
                          "    {\n" +
                          "      \"set\": {\n" +
                          "        \"description\": \"My optional processor description\",\n" +
                          "        \"field\": \"my-long-field\",\n" +
                          "        \"value\": 10\n" +
                          "      }\n" +
                          "    },\n" +
                          "    {\n" +
                          "      \"set\": {\n" +
                          "        \"description\": \"Set 'my-boolean-field' to true\",\n" +
                          "        \"field\": \"my-boolean-field\",\n" +
                          "        \"value\": true\n" +
                          "      }\n" +
                          "    },\n" +
                          "    {\n" +
                          "      \"lowercase\": {\n" +
                          "        \"field\": \"my-keyword-field\"\n" +
                          "      }\n" +
                          "    }\n" +
                          "  ]\n" +
                          "    },\n" +
                          "    \"api\": \"ingest.put_pipeline\"\n" +
                          "  }\n" +
                          "]";
        RequestConverter requestConverter = new RequestConverter();
        String response = requestConverter.convertToDsl(pipeline, false, "http://localhost:9200/");
        String expected = """
            client.ingest().putPipeline(p -> p
                .description("My optional pipeline description")
                .id("my-pipeline")
                .processors(List.of(Processor.of(pr -> pr
                        .set(s -> s
                            .field("my-long-field")
                            .value(JsonData.fromJson("10"))
                            .description("My optional processor description")
                        )
                    ),Processor.of(pro -> pro
                        .set(s -> s
                            .field("my-boolean-field")
                            .value(JsonData.fromJson("true"))
                            .description("Set 'my-boolean-field' to true")
                        )
                    ),Processor.of(proc -> proc
                        .lowercase(l -> l
                            .field("my-keyword-field")
                        )
                    )))
            );
            """;
        Assert.assertEquals(expected.trim(), response);
    }

    @Test
    public void simulateIngest() {
        String simulateIngest = "[\n" +
                                "  {\n" +
                                "    \"params\": { \"id\": \"my-pipeline\" },\n" +
                                "    \"query\": { \"verbose\": true },\n" +
                                "    \"body\": { \"_source\": {\"my-boolean-field\": false}, \"something\":" +
                                " \"dd\", \"docs\": " +
                                "[\n" +
                                "    {\n" +
                                "      \"_index\": \"index\",\n" +
                                "      \"_id\": \"id\",\n" +
                                "      \"_source\": {\n" +
                                "        \"my-keyword-field\": \"bar\"\n" +
                                "      }\n" +
                                "    },\n" +
                                "    {\n" +
                                "      \"_index\": \"index\",\n" +
                                "      \"_id\": \"id\",\n" +
                                "      \"_source\": {\n" +
                                "        \"my-long-field\": 10\n" +
                                "      }\n" +
                                "    }\n" +
                                "  ] },\n" +
                                "    \"api\": \"ingest.simulate\"\n" +
                                "  }\n" +
                                "]";
        RequestConverter requestConverter = new RequestConverter();
        String response = requestConverter.convertToDsl(simulateIngest, false, "http://localhost:9200/");
        String expected = """
            client.ingest().simulate(s -> s
                .docs(List.of(Document.of(d -> d
                        .id("id")
                        .index("index")
                        .source(JsonData.fromJson("{\\"my-keyword-field\\":\\"bar\\"}"))
                    ),Document.of(d -> d
                        .id("id")
                        .index("index")
                        .source(JsonData.fromJson("{\\"my-long-field\\":10}"))
                    )))
                .id("my-pipeline")
                .verbose(true)
            );
            """;
        Assert.assertEquals(expected.trim(), response);
    }

    @Test
    public void analyzer() {
        String analyzer = "[\n" +
                          "  {\n" +
                          "    \"params\": { \"index\": \"arabic_example\" },\n" +
                          "    \"body\": {\n" +
                          "  \"settings\": {\n" +
                          "    \"analysis\": {\n" +
                          "      \"filter\": {\n" +
                          "        \"arabic_stop\": {\n" +
                          "          \"type\":       \"stop\",\n" +
                          "          \"stopwords\":  \"_arabic_\" \n" +
                          "        },\n" +
                          "        \"arabic_keywords\": {\n" +
                          "          \"type\":       \"keyword_marker\",\n" +
                          "          \"keywords\":   [\"مثال\"] \n" +
                          "        },\n" +
                          "        \"arabic_stemmer\": {\n" +
                          "          \"type\":       \"stemmer\",\n" +
                          "          \"language\":   \"arabic\"\n" +
                          "        }\n" +
                          "      },\n" +
                          "      \"analyzer\": {\n" +
                          "        \"rebuilt_arabic\": {\n" +
                          "          \"tokenizer\":  \"standard\",\n" +
                          "          \"filter\": [\n" +
                          "            \"lowercase\",\n" +
                          "            \"decimal_digit\",\n" +
                          "            \"arabic_stop\",\n" +
                          "            \"arabic_normalization\",\n" +
                          "            \"arabic_keywords\",\n" +
                          "            \"arabic_stemmer\"\n" +
                          "          ]\n" +
                          "        }\n" +
                          "      }\n" +
                          "    }\n" +
                          "  }\n" +
                          "},\n" +
                          "    \"api\": \"indices.create\"\n" +
                          "\n" +
                          "  }\n" +
                          "]";
        RequestConverter requestConverter = new RequestConverter();
        String response = requestConverter.convertToDsl(analyzer, false, "http://localhost:9200/");
        String expected = """
            client.indices().create(c -> c
                .index("arabic_example")
                .settings(s -> s
                    .analysis(a -> a
                        .analyzer("rebuilt_arabic", an -> an
                            .custom(cu -> cu
                                .filter(List.of("lowercase","decimal_digit","arabic_stop","arabic_normalization","arabic_keywords","arabic_stemmer"))
                                .tokenizer("standard")
                            )
                        )
                        .filter(Map.of("arabic_keywords", TokenFilter.of(t -> t
                                .definition(d -> d
                                    .keywordMarker(k -> k
                                        .keywords("مثال")
                                    )
                                )
                            ),"arabic_stop", TokenFilter.of(to -> to
                                .definition(de -> de
                                    .stop(st -> st
                                        .stopwords("_arabic_")
                                    )
                                )
                            ),"arabic_stemmer", TokenFilter.of(tok -> tok
                                .definition(def -> def
                                    .stemmer(st -> st
                                        .language("arabic")
                                    )
                                )
                            )))
                    )
                )
            );
            """;
        Assert.assertEquals(expected.trim(), response);
    }

    @Test
    public void multimapAgg() {
        String multimapAgg = "[\n" +
                             "  {\n" +
                             "    \"params\": { \"index\": \"my-index-000001\" },\n" +
                             "    \"query\": { \"from\": 40, \"size\": 20 },\n" +
                             "    \"body\": {\n" +
                             "  \"aggs\": {\n" +
                             "    \"my-first-agg-name\": {\n" +
                             "      \"terms\": {\n" +
                             "        \"field\": \"my-field\"\n" +
                             "      }\n" +
                             "    },\n" +
                             "    \"my-second-agg-name\": {\n" +
                             "      \"avg\": {\n" +
                             "        \"field\": \"my-other-field\"\n" +
                             "      }\n" +
                             "    }\n" +
                             "  }\n" +
                             "}" +
                             ",\n" +
                             "    \"api\": \"search\"\n" +
                             "  }\n" +
                             "]";
        RequestConverter requestConverter = new RequestConverter();
        String response = requestConverter.convertToDsl(multimapAgg, false, "http://localhost:9200/");
        String expected = """
            client.search(s -> s
                .aggregations(Map.of("my-second-agg-name", Aggregation.of(a -> a
                        .avg(av -> av
                            .field("my-other-field")
                        )
                    ),"my-first-agg-name", Aggregation.of(ag -> ag
                        .terms(t -> t
                            .field("my-field")
                        )
                    )))
                .from(40)
                .index("my-index-000001")
                .size(20)
            ,Void.class);
            """;
        Assert.assertEquals(expected.trim(), response);
    }

    //@Test
    public void willBreak_ScriptMustache() {
        // TODO fix
        String willBreak_ScriptMustache = "[\n" +
                                          "  {\n" +
                                          "    \"params\": { \"id\": \"my-search-template\" },\n" +
                                          "    \"body\": {\n" +
                                          "  \"script\": {\n" +
                                          "    \"lang\": \"mustache\",\n" +
                                          "    \"source\": {\n" +
                                          "      \"query\": {\n" +
                                          "        \"match\": {\n" +
                                          "          \"message\": \"{{query_string}}\"\n" +
                                          "        }\n" +
                                          "      },\n" +
                                          "      \"from\": \"{{from}}\",\n" +
                                          "      \"size\": \"{{size}}\"\n" +
                                          "    }\n" +
                                          "  }\n" +
                                          "}" +
                                          ",\n" +
                                          "    \"api\": \"put_script\"\n" +
                                          "  }\n" +
                                          "]";
        RequestConverter requestConverter = new RequestConverter();
        String response = requestConverter.convertToDsl(willBreak_ScriptMustache, false, "http://localhost" +
                                                                                         ":9200/");
        System.out.println(response);
        String expected = """
            """;
        Assert.assertEquals(expected.trim(), response);
    }

    @Test
    public void knn() {
        String knn = "[\n" +
                     "  {\n" +
                     "    \"params\": { \"index\": \"my-index-000001\" },\n" +
                     "    \"query\": { \"from\": 40, \"size\": 20 },\n" +
                     "    \"body\": {\n" +
                     "  \"knn\": {\n" +
                     "    \"field\": \"image-vector\",\n" +
                     "    \"query_vector\": [0.1, -2],\n" +
                     "    \"k\": 15,\n" +
                     "    \"num_candidates\": 100\n" +
                     "  },\n" +
                     "  \"fields\": [ \"title\" ],\n" +
                     "  \"rescore\": {\n" +
                     "    \"window_size\": 10,\n" +
                     "    \"query\": {\n" +
                     "      \"rescore_query\": {\n" +
                     "        \"script_score\": {\n" +
                     "          \"query\": {\n" +
                     "            \"match_all\": {}\n" +
                     "          },\n" +
                     "          \"script\": {\n" +
                     "            \"source\": \"cosineSimilarity(params.query_vector, 'image-vector') + 1" +
                     ".0\",\n" +
                     "            \"params\": {\n" +
                     "              \"query_vector\": [0.1, -2]\n" +
                     "            }\n" +
                     "          }\n" +
                     "        }\n" +
                     "      }\n" +
                     "    }\n" +
                     "  }\n" +
                     "}" +
                     ",\n" +
                     "    \"api\": \"search\"\n" +
                     "  }\n" +
                     "]";
        RequestConverter requestConverter = new RequestConverter();
        String response = requestConverter.convertToDsl(knn, false, "http://localhost:9200/");
        String expected = """
            client.search(s -> s
                .fields(f -> f
                    .field("title")
                )
                .from(40)
                .index("my-index-000001")
                .knn(k -> k
                    .field("image-vector")
                    .queryVector(List.of(0.1F,-2.0F))
                    .k(15)
                    .numCandidates(100)
                )
                .rescore(r -> r
                    .query(q -> q
                        .query(qu -> qu
                            .scriptScore(sc -> sc
                                .query(que -> que
                                    .matchAll(m -> m)
                                )
                                .script(scr -> scr
                                    .source(so -> so
                                        .scriptString("cosineSimilarity(params.query_vector, 'image-vector') + 1.0")
                                    )
                                    .params("query_vector", JsonData.fromJson("[0.1,-2]"))
                                )
                            )
                        )
                    )
                    .windowSize(10)
                )
                .size(20)
            ,Void.class);
            """;
        Assert.assertEquals(expected.trim(), response);
    }

    @Test
    public void nestedLoopTest() {
        String nestedLoopTest = "[\n" +
                                "  {\n" +
                                "    \"params\": { \"index\": \"my-index-000001\" },\n" +
                                "    \"query\": { \"from\": 40, \"size\": 20 },\n" +
                                "    \"body\": {\n" +
                                "  \"query\": {\n" +
                                "    \"nested\": {\n" +
                                "      \"path\": \"driver\",\n" +
                                "      \"query\": {\n" +
                                "        \"nested\": {\n" +
                                "          \"path\": \"driver.vehicle\",\n" +
                                "          \"query\": {\n" +
                                "            \"nested\": {\n" +
                                "              \"path\": \"driver.vehicle.wheel\",\n" +
                                "              \"query\": {\n" +
                                "                \"nested\": {\n" +
                                "                  \"path\": \"driver.vehicle.wheel.nut\",\n" +
                                "                  \"query\": {\n" +
                                "                    \"nested\": {\n" +
                                "                      \"path\": \"driver.vehicle.wheel.nut.metal\",\n" +
                                "                      \"query\": {\n" +
                                "                        \"nested\": {\n" +
                                "                          \"path\": \"driver.vehicle.wheel.nut.metal" +
                                ".atom\",\n" +
                                "                          \"query\": {\n" +
                                "                            \"match_all\": {}\n" +
                                "                          }\n" +
                                "                        }\n" +
                                "                      }\n" +
                                "                    }\n" +
                                "                  }\n" +
                                "                }\n" +
                                "              }\n" +
                                "            }\n" +
                                "          }\n" +
                                "        }\n" +
                                "      }\n" +
                                "    }\n" +
                                "  }\n" +
                                "}" +
                                ",\n" +
                                "    \"api\": \"search\"\n" +
                                "  }\n" +
                                "]";
        RequestConverter requestConverter = new RequestConverter();
        String response = requestConverter.convertToDsl(nestedLoopTest, false, "http://localhost:9200/");
        System.out.println(response);
        String expected = """
            client.search(s -> s
                .from(40)
                .index("my-index-000001")
                .query(q -> q
                    .nested(n -> n
                        .path("driver")
                        .query(qu -> qu
                            .nested(ne -> ne
                                .path("driver.vehicle")
                                .query(que -> que
                                    .nested(nes -> nes
                                        .path("driver.vehicle.wheel")
                                        .query(quer -> quer
                                            .nested(nest -> nest
                                                .path("driver.vehicle.wheel.nut")
                                                .query(query -> query
                                                    .nested(neste -> neste
                                                        .path("driver.vehicle.wheel.nut.metal")
                                                        .query(query1 -> query1
                                                            .nested(nested -> nested
                                                                .path("driver.vehicle.wheel.nut.metal.atom")
                                                                .query(query2 -> query2
                                                                    .matchAll(m -> m)
                                                                )
                                                            )
                                                        )
                                                    )
                                                )
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
                .size(20)
            ,Void.class);
            """;
        Assert.assertEquals(expected.trim(), response);
    }

    @Test
    public void distanceFeatureQuery() {
        String distanceFeatureQuery = "[\n" +
                                      "  {\n" +
                                      "    \"params\": { \"index\": \"my-index-000001\" },\n" +
                                      "    \"query\": { \"from\": 40, \"size\": 20 },\n" +
                                      "    \"body\": {\n" +
                                      "  \"query\": {\n" +
                                      "    \"bool\": {\n" +
                                      "      \"must\": {\n" +
                                      "        \"match\": {\n" +
                                      "          \"name\": \"chocolate\"\n" +
                                      "        }\n" +
                                      "      },\n" +
                                      "      \"should\": {\n" +
                                      "        \"distance_feature\": {\n" +
                                      "          \"field\": \"location\",\n" +
                                      "          \"pivot\": \"1000m\",\n" +
                                      "          \"origin\": [-71.3, 41.15]\n" +
                                      "        }\n" +
                                      "      }\n" +
                                      "    }\n" +
                                      "  }\n" +
                                      "}" +
                                      ",\n" +
                                      "    \"api\": \"search\"\n" +
                                      "  }\n" +
                                      "]";
        RequestConverter requestConverter = new RequestConverter();
        String response = requestConverter.convertToDsl(distanceFeatureQuery, false, "http://localhost:9200" +
                                                                                     "/");
        String expected = """
            client.search(s -> s
                .from(40)
                .index("my-index-000001")
                .query(q -> q
                    .bool(b -> b
                        .must(m -> m
                            .match(ma -> ma
                                .field("name")
                                .query(FieldValue.of("chocolate"))
                            )
                        )
                        .should(sh -> sh
                            .distanceFeature(d -> d
                                .untyped(u -> u
                                    .origin(JsonData.fromJson("[-71.3,41.15]"))
                                    .pivot(JsonData.fromJson("\\"1000m\\""))
                                    .field("location")
                                )
                            )
                        )
                    )
                )
                .size(20)
            ,Void.class);
            """;
        Assert.assertEquals(expected.trim(), response);
    }

    @Test
    public void subAggregationNamedValue() {
        String subAggregationNamedValue = "[\n" +
                                          "  {\n" +
                                          "    \"body\": {\n" +
                                          "  \"size\": 0,\n" +
                                          "  \"query\": {\n" +
                                          "    \"bool\": {\n" +
                                          "      \"filter\": [\n" +
                                          "        {\n" +
                                          "          \"term\": {\n" +
                                          "            \"is_sold\": true\n" +
                                          "          }\n" +
                                          "        },\n" +
                                          "        {\n" +
                                          "          \"term\": {\n" +
                                          "            \"lender_id\": 4477943\n" +
                                          "          }\n" +
                                          "        }\n" +
                                          "      ]\n" +
                                          "    }\n" +
                                          "  },\n" +
                                          "  \"aggs\": {\n" +
                                          "    \"group_by_summaryGroup\": {\n" +
                                          "      \"terms\": {\n" +
                                          "        \"field\": \"group.keyword\",\n" +
                                          "        \"order\": {\n" +
                                          "          \"_key\": \"desc\"\n" +
                                          "        }\n" +
                                          "      },\n" +
                                          "      \"aggs\": {\n" +
                                          "        \"note_count\": {\n" +
                                          "          \"value_count\": {\n" +
                                          "            \"field\": \"id\"\n" +
                                          "          }\n" +
                                          "        },\n" +
                                          "        \"invested_sum\": {\n" +
                                          "          \"sum\": {\n" +
                                          "            \"field\": \"amount_participation\"\n" +
                                          "          }\n" +
                                          "        },\n" +
                                          "        \"outstanding_principal_sum\": {\n" +
                                          "          \"sum\": {\n" +
                                          "            \"field\": \"principal_balance\"\n" +
                                          "          }\n" +
                                          "        },\n" +
                                          "        \"principal_repaid_sum\": {\n" +
                                          "          \"sum\": {\n" +
                                          "            \"field\": \"principal_repaid\"\n" +
                                          "          }\n" +
                                          "        },\n" +
                                          "        \"interest_paid_sum\": {\n" +
                                          "          \"sum\": {\n" +
                                          "            \"field\": \"interest_paid\"\n" +
                                          "          }\n" +
                                          "        }\n" +
                                          "      }\n" +
                                          "    }\n" +
                                          "  }\n" +
                                          "}" +
                                          ",\n" +
                                          "    \"api\": \"search\"\n" +
                                          "  }\n" +
                                          "]";
        RequestConverter requestConverter = new RequestConverter();
        String response = requestConverter.convertToDsl(subAggregationNamedValue, false, "http://localhost" +
                                                                                         ":9200/");
        String expected = """
            client.search(s -> s
                .aggregations("group_by_summaryGroup", a -> a
                    .terms(t -> t
                        .field("group.keyword")
                        .order(NamedValue.of("_key",SortOrder.Desc))
                    )
                    .aggregations(Map.of("interest_paid_sum", Aggregation.of(ag -> ag
                            .sum(su -> su
                                .field("interest_paid")
                            )
                        ),"outstanding_principal_sum", Aggregation.of(agg -> agg
                            .sum(su -> su
                                .field("principal_balance")
                            )
                        ),"principal_repaid_sum", Aggregation.of(aggr -> aggr
                            .sum(su -> su
                                .field("principal_repaid")
                            )
                        ),"invested_sum", Aggregation.of(aggre -> aggre
                            .sum(su -> su
                                .field("amount_participation")
                            )
                        ),"note_count", Aggregation.of(aggreg -> aggreg
                            .valueCount(v -> v
                                .field("id")
                            )
                        )))
                )
                .query(q -> q
                    .bool(b -> b
                        .filter(List.of(Query.of(qu -> qu
                                .term(t -> t
                                    .field("is_sold")
                                    .value(FieldValue.of(true))
                                )
                            ),Query.of(que -> que
                                .term(t -> t
                                    .field("lender_id")
                                    .value(FieldValue.of(4477943))
                                )
                            )))
                    )
                )
                .size(0)
            ,Void.class);
            """;
        Assert.assertEquals(expected.trim(), response);
    }
}
