# Elasticsearch Java Client Request Converter

Experimental converter from elasticsearch json requests to [Java client](https://github.com/elastic/elasticsearch-java) requests, as seen in the [api docs](https://www.elastic.co/docs/api/doc/elasticsearch/).

![Screenshot](/readme-resources/request-convert-example.gif)

## Usage

The converter does not work on its own, it relies on the common [request-converter](https://github.com/elastic/request-converter) to perform an intermediate conversion from the Elasticsearch json request to a more structured/typed request, then converts the intermediate format to a java request.
The common request-converter is a node library, so the easier way to use both libraries is to write a simple js/typescript script which calls the request-converter first and then calls the java request converter .jar using the [java-caller](https://www.npmjs.com/package/java-caller) library.
A full example of a javascript script is provided in this repo at [usage.js](usage/usage.js)

## Full Example

First of all generate the jar file by running `gradle jar`.

Starting from a json request: 
```json
GET /my-index-000001/_search?size=20
  {
    "query": {
      "term": {
        "user.id": "kimchy"
      }
    }
  }
```

it needs to be passed to the .parseRequest() method of the request-converter, which will return this:

```json
[
  {
    params: { index: 'my-index-000001' },
    method: 'GET',
    url: '/my-index-000001/_search?size=20',
    path: '/my-index-000001/_search',
    rawPath: '/my-index-000001/_search',
    query: { size: '20' },
    body: { query: [Object] },
    api: 'search',
    request: {
      attachedBehaviors: [Array],
      body: [Object],
      description: 'Returns search hits that match the query defined in the request.\n' +
        'You can provide search queries using the `q` query string parameter or the request body.\n' +
        'If both are specified, only the query parameter is used.',
      inherits: [Object],
      kind: 'request',
      name: [Object],
      path: [Array],
      query: [Array],
      specLocation: '_global/search/SearchRequest.ts#L54-L530'
    }
  }
]
```

This cannot be sent as is to the java request converter, it needs a bit of tweaking: some fields have aliases in the specification, and the java clients uses those, so use the `getCodeGenParamNames` in `usage.js` to set the correct names; then prepare a json object with this structure:

```json
[
  {
    "params": {},
    "query": {},
    "body": {},
    "api": "api-name"
  }
]
```
and fill it with the correct fields from the intermediate request (replacing `params` with the ones from `getCodeGenParamNames`).
It should look like this:

```json
[
  {
    params: { index: 'my-index-000001' },
    query: { size: '20' },
    body: {
     "query": {
       "term": {
         "user.id": {
         "value": "kimchy"
       }
       }
     }
    },
    api: 'search',
  }
]
```

Now this can be sent to the java request converter as the first argument of the jar, and the client call will be returned. 

```java
client.search(s -> s
    .index("my-index-000001")
    .query(q -> q
        .term(t -> t
            .field("user.id")
            .value(FieldValue.of("kimchy"))
        )
    )
    .size(20)
,Void.class);
```
The jar accepts two more arguments:
* a boolean, if `true` the converter will return a full class instead of just single requests, complete with imports and the client setup.
* a string which will be used as server url in case the previous arg is `true`

Of course the jar can also be run with `java -jar`, but it's not really recommended since the json requests will have to be all escaped, for example:

```shell
java -jar java-es-request-converter-1.0-SNAPSHOT.jar  "[{\"api\":\"indices.update_aliases\",\"params\":{},\"body\":{\"actions\":[{\"add\":{\"index\":\"test-bulk\",\"alias\":\"my-alias-bulk\"}}]}},{\"api\":\"indices.get_alias\",\"params\":{\"name\":\"my-alias-bulk,test2\"}}] true server-url"
```
