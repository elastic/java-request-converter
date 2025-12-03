/*
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

const {parseRequest} = require("@elastic/request-converter/dist/parse");

function getCodeGenParamNames(
    params,
    request,
){
    for (const [key, value] of Object.entries(params)) {
        if (request?.path) {
            for (const prop of request.path) {
                if (prop.name === key && prop.codegenName !== undefined) {
                    delete params[key];
                    params[prop.codegenName] = value;
                }
            }
        }
    }
    return params;
}

async function main() {
    //given the json request as script parameter
    const jsonRequest = process.argv[2];

    // intermediate conversion to request-converter
    const partialRequest = await parseRequest(jsonRequest);

    // replacing parameter names with @codegen_names, where present
    let correctParams = getCodeGenParamNames(partialRequest.params, partialRequest.request);
    let body = partialRequest.body;
    if (!body) {
        body = {}
    }

    // creating json array of object accepted by the java client
    let javaReqs = [];
    const javaParsedRequest = {
        api: partialRequest.api,
        params: correctParams,
        query: partialRequest.query,
        body: body,
    };
    javaReqs.push(javaParsedRequest)

    let args = [];
    args.push(JSON.stringify(javaReqs));

    const { stdout, stderr } = await execAsync(
        `java -jar ${process.env.JAVA_ES_REQUEST_CONVERTER_JAR} args false ""`,
    );

    // error
    if (!stdout) {
        console.log(stderr);
        console.log(JSON.stringify(javaReqs));
    }
    // success!
    else {
        console.log(stdout);
        return stdout;
    }
}

main();
