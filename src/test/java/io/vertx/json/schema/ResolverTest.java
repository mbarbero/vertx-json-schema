package io.vertx.json.schema;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.pointer.JsonPointer;
import io.vertx.junit5.VertxExtension;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@ExtendWith(VertxExtension.class)
public class ResolverTest {

  @Test
  public void testResolveCircularRefs() {

    JsonObject schema = new JsonObject("{\"$id\":\"http://www.example.com/\",\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"definitions\":{\"address\":{\"type\":\"object\",\"properties\":{\"street_address\":{\"type\":\"string\"},\"city\":{\"type\":\"string\"},\"state\":{\"type\":\"string\"},\"subAddress\":{\"$ref\":\"http://www.example.com/#/definitions/address\"}}},\"req\":{\"required\":[\"billing_address\"]}},\"type\":\"object\",\"properties\":{\"billing_address\":{\"$ref\":\"#/definitions/address\"},\"shipping_address\":{\"$ref\":\"#/definitions/address\"}},\"$ref\":\"#/definitions/req\"}");

    JsonObject res = Ref.resolve(schema);
    // this is null because the top level ref implies a full object replacement
    assertThat(res.getJsonObject("properties")).isNull();
  }

  @Test
  public void testResolveCircularRefs2() {

    SchemaRepository repo = SchemaRepository.create(new JsonSchemaOptions().setBaseUri("http://vertx.io"));

    JsonObject schema =
      new JsonObject("{\"$id\":\"http://www.example.com/\",\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"definitions\":{\"address\":{\"type\":\"object\",\"properties\":{\"street_address\":{\"type\":\"string\"},\"city\":{\"type\":\"string\"},\"state\":{\"type\":\"string\"},\"subAddress\":{\"$ref\":\"http://www.example.com/#/definitions/address\"}}},\"req\":{\"required\":[\"billing_address\"]}},\"type\":\"object\",\"properties\":{\"billing_address\":{\"$ref\":\"#/definitions/address\"},\"shipping_address\":{\"$ref\":\"#/definitions/address\"}}}");

    JsonObject res = Ref.resolve(schema);

    assertThat(JsonPointer.from("/properties/billing_address/properties/city/type").queryJson(res)).isEqualTo("string");

    // circular check
    assertThat(JsonPointer.from("/properties/billing_address/properties/subAddress/$ref").queryJson(res)).isNull();
    assertThat(JsonPointer.from("/properties/billing_address/properties/subAddress/type").queryJson(res)).isEqualTo("object");
  }

  @Test
  public void testRefResolverFail() throws IOException {
    try {
      JsonObject res =
        Ref.resolve(
          new JsonObject(Buffer.buffer(Files.readAllBytes(Paths.get("src", "test", "resources", "ref_test", "person_draft201909.json")))));

      fail("Should not reach here");
    } catch (UnsupportedOperationException e) {
      // OK
    }
  }

  @Test
  public void testResolveRefsFromRepositoryWithRefs(Vertx vertx) {
    SchemaRepository repository = SchemaRepository.create(new JsonSchemaOptions().setDraft(Draft.DRAFT4).setBaseUri(
      "https://vertx.io"));

    for (String uri : Arrays.asList("pet.api.json", "pet.model.json", "store.api.json", "store.model" +
      ".json", "user.api.json", "user.model.json")) {
      repository
        .dereference(uri, JsonSchema.of(new JsonObject(vertx.fileSystem().readFileBlocking("resolve/" + uri))));
    }

    JsonObject apiJson = new JsonObject(vertx.fileSystem().readFileBlocking("resolve/api.json"));
    JsonObject apiResolved = new JsonObject(vertx.fileSystem().readFileBlocking("resolve/api_resolved.json"));
    assertThat(new JsonObject(repository.resolve(apiJson).encode())).isEqualTo(apiResolved);
  }

  @Test
  public void testResolveRefsFromRepositoryWithRefsByRef(Vertx vertx) {
    SchemaRepository repository = SchemaRepository.create(new JsonSchemaOptions().setDraft(Draft.DRAFT4).setBaseUri(
      "https://vertx.io"));

    for (String uri : Arrays.asList("api.json", "pet.api.json", "pet.model.json", "store.api.json", "store.model" +
      ".json", "user.api.json", "user.model.json")) {
      repository
        .dereference(uri, JsonSchema.of(new JsonObject(vertx.fileSystem().readFileBlocking("resolve/" + uri))));
    }

    JsonObject apiResolved = new JsonObject(vertx.fileSystem().readFileBlocking("resolve/api_resolved_by_ref.json"));
    assertThat(repository.resolve("api.json")).containsExactlyInAnyOrderElementsOf(apiResolved);
  }

  @Test
  public void testResolveRefsWithinArray(Vertx vertx) {

    JsonObject schema = new JsonObject(vertx.fileSystem().readFileBlocking("resolve/array.json"));
    JsonObject json = Ref.resolve(schema);

    assertThat(json.getJsonArray("parameters").getValue(0))
      .isInstanceOf(JsonObject.class);
  }

  @Test
  public void testResolveRefsFromOpenAPISource(Vertx vertx) {
    SchemaRepository repository =
      SchemaRepository.create(new JsonSchemaOptions().setDraft(Draft.DRAFT202012).setBaseUri("app://"));
    repository.preloadMetaSchema(vertx.fileSystem());

    JsonObject apiJson = new JsonObject(vertx.fileSystem().readFileBlocking("resolve/guestbook_api.json"));
    JsonSchema apiSchema = JsonSchema.of(apiJson);
    repository.dereference(apiSchema);

    JsonObject componentsJson = new JsonObject(vertx.fileSystem().readFileBlocking("resolve/guestbook_components" +
      ".json"));
    String componentsRef = "https://example.com/guestbook/components";
    repository.dereference(componentsRef, JsonSchema.of(componentsJson));

    JsonObject expectedJson = new JsonObject(vertx.fileSystem().readFileBlocking("resolve/guestbook_bundle.json"));
    assertThat(repository.resolve(apiJson)).isEqualTo(expectedJson);
    assertThat(repository.resolve(apiSchema)).isEqualTo(expectedJson);
  }

  @Test
  public void testResolveShouldHaveNoRefReferences(Vertx vertx) {

    Buffer source = vertx.fileSystem().readFileBlocking("resolve/petstore.json");
    Pattern ref = Pattern.compile("\\$ref", Pattern.MULTILINE);
    assertThat(ref.matcher(source.toString()).find()).isTrue();

    JsonObject json = Ref.resolve(new JsonObject(source));

    assertThat(ref.matcher(json.encode()).find()).isFalse();
  }

  @Test
  public void testResolveCircularRefsDoWork(Vertx vertx) {

    SchemaRepository repo = SchemaRepository.create(new JsonSchemaOptions().setBaseUri("http://vertx.io"));

    JsonObject document = new JsonObject("{\"$id\":\"http://www.example.com/\",\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"definitions\":{\"address\":{\"type\":\"object\",\"properties\":{\"street_address\":{\"type\":\"string\"},\"city\":{\"type\":\"string\"},\"state\":{\"type\":\"string\"},\"subAddress\":{\"$ref\":\"http://www.example.com/#/definitions/address\"}}},\"req\":{\"required\":[\"billing_address\"]}},\"type\":\"object\",\"properties\":{\"billing_address\":{\"$ref\":\"#/definitions/address\"},\"shipping_address\":{\"$ref\":\"#/definitions/address\"}}}");

    JsonSchema schema = JsonSchema.of(document);

    repo.dereference(schema);

    JsonObject res = Ref.resolve(document);

    // wrap it in a schema
    JsonSchema schema2 = JsonSchema.of(res);

    // simple check
    JsonObject fixture = new JsonObject(vertx.fileSystem().readFileBlocking("resolve/circular.json"));

    // Canary
    OutputUnit result = repo.validator(schema).validate(fixture);
    assertThat(result.getValid()).isTrue();

    // Real test (given that the resolved holds the dereferenced metadata, it works as it picks the dereferneced schema
    // from the __absolute_uri__ field
    OutputUnit result2 = repo.validator(schema2).validate(fixture);
    assertThat(result2.getValid()).isTrue();
  }

  @Test
  public void testOpenAPI31(Vertx vertx) {

    // this looks like a useless test but it is used to catch regressions on references that change after being resolved
    // given that they are now properly computed the resolved references are always updated, while in the past they were
    // copied leaving the original reference unchanged and producing the wrong expanded document.
    Buffer source = vertx.fileSystem().readFileBlocking("resolve/petstore_31.json");
    Buffer expected = vertx.fileSystem().readFileBlocking("resolve/petstore_31_resolved.json");

    JsonObject expectedJson = new JsonObject(expected);
    JsonObject json = Ref.resolve(new JsonObject(source));
    assertThat(json).isEqualTo(expectedJson);
    json = JsonSchema.of(new JsonObject(source)).resolve();
    assertThat(json).isEqualTo(expectedJson);
  }
}
