package com.devmind.tool.openapi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiParserTest {

    private final OpenApiParser parser = new OpenApiParser();

    @Test
    void parsesJsonDocument() {
        String json = """
                {
                  "openapi": "3.0.1",
                  "info": { "title": "订单服务", "version": "1.0" },
                  "servers": [ { "url": "https://api.example.com" } ],
                  "paths": {
                    "/orders": {
                      "get": {
                        "operationId": "listOrders",
                        "summary": "查询订单列表",
                        "description": "按条件分页查询订单",
                        "tags": ["订单"],
                        "parameters": [
                          { "name": "page", "in": "query", "required": false,
                            "schema": { "type": "integer" }, "description": "页码" },
                          { "name": "status", "in": "query",
                            "schema": { "type": "string" } }
                        ]
                      },
                      "post": {
                        "operationId": "createOrder",
                        "summary": "创建订单",
                        "requestBody": {
                          "content": {
                            "application/json": {
                              "schema": {
                                "type": "object",
                                "properties": {
                                  "amount": { "type": "number" },
                                  "userId": { "type": "integer" }
                                },
                                "required": ["amount"]
                              }
                            }
                          }
                        }
                      }
                    },
                    "/orders/{id}": {
                      "get": {
                        "operationId": "getOrderById",
                        "summary": "查询订单详情",
                        "parameters": [
                          { "name": "id", "in": "path", "required": true,
                            "schema": { "type": "integer" } }
                        ]
                      },
                      "delete": {
                        "operationId": "cancelOrder",
                        "summary": "取消订单"
                      }
                    },
                    "/health": {
                      "get": { "summary": "健康检查" },
                      "head": { "summary": "head 探活（应被跳过）" }
                    }
                  }
                }
                """;

        OpenApiParser.ParsedDocument doc = parser.parse(json, "orders.json");

        assertThat(doc.title()).isEqualTo("订单服务");
        assertThat(doc.baseUrl()).isEqualTo("https://api.example.com");
        assertThat(doc.operations()).hasSize(5); // get/post + get/delete + get(head 跳过)

        OpenApiOperation list = doc.operations().get(0);
        assertThat(list.method()).isEqualTo("GET");
        assertThat(list.path()).isEqualTo("/orders");
        assertThat(list.operationId()).isEqualTo("listOrders");
        assertThat(list.summary()).isEqualTo("查询订单列表");
        assertThat(list.tags()).containsExactly("订单");
        assertThat(list.parameters()).hasSize(2);
        assertThat(list.parameters().get(0).name()).isEqualTo("page");
        assertThat(list.parameters().get(0).in()).isEqualTo("query");
        assertThat(list.parameters().get(0).type()).isEqualTo("integer");

        // requestBody 提取
        OpenApiOperation create = doc.operations().get(1);
        assertThat(create.requestBodyJson()).contains("amount");

        // 无 operationId 时保留 summary
        OpenApiOperation health = doc.operations().get(4);
        assertThat(health.operationId()).isEmpty();
        assertThat(health.summary()).isEqualTo("健康检查");
    }

    @Test
    void parsesYamlDocument() {
        String yaml = """
                openapi: 3.0.3
                info:
                  title: 用户服务
                  version: 1.0.0
                paths:
                  /users:
                    get:
                      operationId: listUsers
                      summary: 查询用户列表
                      parameters:
                        - name: keyword
                          in: query
                          schema:
                            type: string
                  /users/{id}:
                    put:
                      operationId: updateUser
                      summary: 更新用户
                """;

        OpenApiParser.ParsedDocument doc = parser.parse(yaml, "users.yaml");

        assertThat(doc.title()).isEqualTo("用户服务");
        assertThat(doc.operations()).hasSize(2);
        assertThat(doc.operations().get(0).method()).isEqualTo("GET");
        assertThat(doc.operations().get(1).method()).isEqualTo("PUT");
        assertThat(doc.operations().get(1).path()).isEqualTo("/users/{id}");
    }

    @Test
    void rejectsNonOpenApi3() {
        String json = """
                { "openapi": "2.0", "info": { "title": "旧版" }, "paths": {} }
                """;
        assertThatThrownBy(() -> parser.parse(json, "a.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OpenAPI 3.x");
    }

    @Test
    void rejectsMissingPaths() {
        String json = """
                { "openapi": "3.0.0", "info": { "title": "无接口" } }
                """;
        assertThatThrownBy(() -> parser.parse(json, "a.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paths");
    }

    @Test
    void rejectsBlankContent() {
        assertThatThrownBy(() -> parser.parse("   ", "a.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("为空");
    }

    @Test
    void skipsUnsupportedMethodsButKeepsSupported() {
        String json = """
                {
                  "openapi": "3.0.0",
                  "info": { "title": "混合" },
                  "paths": {
                    "/a": { "trace": { "summary": "trace" }, "get": { "operationId": "getA" } },
                    "/b": { "patch": { "summary": "patch" }, "post": { "operationId": "postB" } }
                  }
                }
                """;
        OpenApiParser.ParsedDocument doc = parser.parse(json, "a.json");
        List<OpenApiOperation> ops = doc.operations();
        assertThat(ops).hasSize(2);
        assertThat(ops.get(0).method()).isEqualTo("GET");
        assertThat(ops.get(1).method()).isEqualTo("POST");
    }

    @Test
    void extractsSchemaFromFormUrlencodedRequestBody() {
        // 回归：Stripe 等 API 用 application/x-www-form-urlencoded 请求体，
        // 修复前只认 application/json，form schema 为空导致 Agent 无法传参
        String json = """
                {
                  "openapi": "3.0.1",
                  "info": { "title": "支付服务" },
                  "paths": {
                    "/v1/customers": {
                      "post": {
                        "operationId": "createCustomer",
                        "summary": "创建客户",
                        "requestBody": {
                          "required": true,
                          "content": {
                            "application/x-www-form-urlencoded": {
                              "schema": {
                                "type": "object",
                                "properties": {
                                  "name": { "type": "string" },
                                  "email": { "type": "string" }
                                },
                                "required": ["name"]
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;

        OpenApiParser.ParsedDocument doc = parser.parse(json, "stripe.json");
        OpenApiOperation op = doc.operations().get(0);
        assertThat(op.requestBodyJson()).contains("name");
        assertThat(op.requestBodyJson()).contains("email");
        assertThat(op.requestBodyJson()).doesNotContain("$ref");
    }

    @Test
    void prefersJsonOverFormWhenBothPresent() {
        String json = """
                {
                  "openapi": "3.0.1",
                  "info": { "title": "双格式" },
                  "paths": {
                    "/x": {
                      "post": {
                        "operationId": "doX",
                        "summary": "操作",
                        "requestBody": {
                          "content": {
                            "application/x-www-form-urlencoded": {
                              "schema": { "type": "object", "properties": { "f": { "type": "string" } } }
                            },
                            "application/json": {
                              "schema": { "type": "object", "properties": { "j": { "type": "string" } } }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;

        OpenApiParser.ParsedDocument doc = parser.parse(json, "x.json");
        OpenApiOperation op = doc.operations().get(0);
        assertThat(op.requestBodyJson()).contains("\"j\"");
        assertThat(op.requestBodyJson()).doesNotContain("\"f\"");
    }

    @Test
    void inlinesNestedRefInRequestBodySchema() {
        String json = """
                {
                  "openapi": "3.0.1",
                  "info": { "title": "嵌套引用" },
                  "paths": {
                    "/users": {
                      "post": {
                        "operationId": "createUser",
                        "summary": "创建用户",
                        "requestBody": {
                          "content": {
                            "application/json": {
                              "schema": { "$ref": "#/components/schemas/User" }
                            }
                          }
                        }
                      }
                    }
                  },
                  "components": {
                    "schemas": {
                      "User": {
                        "type": "object",
                        "properties": {
                          "name": { "type": "string" },
                          "address": { "$ref": "#/components/schemas/Address" }
                        }
                      },
                      "Address": {
                        "type": "object",
                        "properties": {
                          "city": { "type": "string" },
                          "country": { "$ref": "#/components/schemas/Country" }
                        }
                      },
                      "Country": {
                        "type": "object",
                        "properties": { "code": { "type": "string" } }
                      }
                    }
                  }
                }
                """;

        OpenApiParser.ParsedDocument doc = parser.parse(json, "nested.json");
        String body = doc.operations().get(0).requestBodyJson();

        // 嵌套 $ref 全部递归展开：name / address.city / address.country.code 均可读，无残留 $ref
        assertThat(body).contains("\"name\"");
        assertThat(body).contains("\"city\"");
        assertThat(body).contains("\"code\"");
        assertThat(body).doesNotContain("$ref");
    }

    @Test
    void refCycleKeepsPlaceholderWithoutHanging() {
        String json = """
                {
                  "openapi": "3.0.1",
                  "info": { "title": "循环引用" },
                  "paths": {
                    "/a": {
                      "post": {
                        "operationId": "createA",
                        "summary": "创建 A",
                        "requestBody": {
                          "content": {
                            "application/json": {
                              "schema": { "$ref": "#/components/schemas/A" }
                            }
                          }
                        }
                      }
                    }
                  },
                  "components": {
                    "schemas": {
                      "A": {
                        "type": "object",
                        "properties": {
                          "fieldA": { "type": "string" },
                          "b": { "$ref": "#/components/schemas/B" }
                        }
                      },
                      "B": {
                        "type": "object",
                        "properties": {
                          "fieldB": { "type": "string" },
                          "a": { "$ref": "#/components/schemas/A" }
                        }
                      }
                    }
                  }
                }
                """;

        // 环（A→B→A）必须终止不挂起：最内层保留 $ref 占位
        OpenApiParser.ParsedDocument doc = parser.parse(json, "cycle.json");
        String body = doc.operations().get(0).requestBodyJson();

        assertThat(body).contains("fieldA");
        assertThat(body).contains("fieldB");
        assertThat(body).contains("$ref"); // 环处保留占位防死循环
    }

    @Test
    void refSiblingDescriptionIsPreserved() {
        // $ref 旁兄弟字段（description 覆盖）应保留
        String json = """
                {
                  "openapi": "3.0.1",
                  "info": { "title": "兄弟覆盖" },
                  "paths": {
                    "/x": {
                      "post": {
                        "operationId": "doX",
                        "summary": "操作",
                        "requestBody": {
                          "content": {
                            "application/json": {
                              "schema": {
                                "$ref": "#/components/schemas/Base",
                                "description": "覆盖描述"
                              }
                            }
                          }
                        }
                      }
                    }
                  },
                  "components": {
                    "schemas": {
                      "Base": { "type": "object", "properties": { "id": { "type": "integer" } } }
                    }
                  }
                }
                """;

        OpenApiParser.ParsedDocument doc = parser.parse(json, "sib.json");
        String body = doc.operations().get(0).requestBodyJson();

        assertThat(body).contains("\"id\"");
        assertThat(body).contains("覆盖描述");
    }
}
