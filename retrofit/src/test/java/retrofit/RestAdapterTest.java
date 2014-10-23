// Copyright 2013 Square, Inc.
package retrofit;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseBody;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.SocketPolicy;
import com.squareup.okhttp.mockwebserver.rule.MockWebServerRule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import retrofit.converter.ConversionException;
import retrofit.http.Body;
import retrofit.http.GET;
import retrofit.http.Headers;
import retrofit.http.POST;
import retrofit.http.Path;
import retrofit.http.Streaming;
import rx.Observable;
import rx.functions.Action1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static retrofit.RestAdapter.LogLevel.BASIC;
import static retrofit.RestAdapter.LogLevel.FULL;
import static retrofit.RestAdapter.LogLevel.HEADERS;
import static retrofit.RestAdapter.LogLevel.HEADERS_AND_ARGS;
import static retrofit.TestingUtils.stringBody;
import static retrofit.Utils.SynchronousExecutor;

public class RestAdapterTest {
  private interface Example {
    @Headers("Foo: Bar")
    @GET("/") Object something();
    @Headers("Foo: Bar")
    @POST("/") Object something(@Body RequestBody body);
    @GET("/") void something(Callback<String> callback);
    @GET("/") ResponseBody direct();
    @GET("/") void direct(Callback<ResponseBody> callback);
    @GET("/") @Streaming ResponseBody streaming();
    @POST("/") Observable<String> observable(@Body String body);
    @POST("/{x}/{y}") Observable<ResponseBody> observable(@Path("x") String x, @Path("y") String y);
  }
  private interface InvalidExample extends Example {
  }

  @Rule public MockWebServerRule mockWebServer = new MockWebServerRule();

  private final List<String> logMessages = new ArrayList<String>();
  private final RestAdapter.Log log = new RestAdapter.Log() {
    @Override public void log(String message) {
      logMessages.add(message);
    }
  };

  private Executor mockRequestExecutor;
  private Executor mockCallbackExecutor;
  private RestAdapter restAdapter;
  private Example example;
  private String host;

  @Before public void setUp() throws Exception{
    mockRequestExecutor = spy(new SynchronousExecutor());
    mockCallbackExecutor = spy(new SynchronousExecutor());

    OkHttpClient client = new OkHttpClient();
    client.setConnectTimeout(1, TimeUnit.SECONDS);
    client.setReadTimeout(1, TimeUnit.SECONDS);
    client.setWriteTimeout(1, TimeUnit.SECONDS);

    restAdapter = new RestAdapter.Builder() //
        .setExecutors(mockRequestExecutor, mockCallbackExecutor)
        .setEndpoint(mockWebServer.getUrl("/").toString())
        .setClient(client)
        .setLog(log)
        .build();
    example = restAdapter.create(Example.class);

    host = mockWebServer.getUrl("/").toString();
  }

  @Test public void objectMethodsStillWork() {
    assertThat(example.hashCode()).isNotZero();
    assertThat(example.equals(this)).isFalse();
    assertThat(example.toString()).isNotEmpty();
  }

  @Test public void interfaceWithExtendIsNotSupported() {
    try {
      new RestAdapter.Builder().setEndpoint("http://foo/").build().create(InvalidExample.class);
      fail("Interface inheritance should not be supported.");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("Interface definitions must not extend other interfaces.");
    }
  }

  @Test public void logRequestResponseBasic() throws Exception {
    restAdapter.setLogLevel(BASIC);
    mockWebServer.enqueue(new MockResponse().setBody("{}"));

    example.something();
    assertThat(logMessages).hasSize(2);
    assertThat(logMessages.get(0)).isEqualTo("---> HTTP GET " + host);
    assertThat(logMessages.get(1)).matches("<--- HTTP 200 " + host + " \\([0-9]+ms\\)");
  }

  @Test public void logRequestResponseHeaders() throws Exception {
    restAdapter.setLogLevel(HEADERS);
    mockWebServer.enqueue(new MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody("Hi"));

    example.something();
    assertThat(logMessages).hasSize(7);
    assertThat(logMessages.get(0)).isEqualTo("---> HTTP GET " + host);
    assertThat(logMessages.get(1)).isEqualTo("Foo: Bar");
    assertThat(logMessages.get(2)).isEqualTo("---> END HTTP (no body)");
    assertThat(logMessages.get(3)).matches("<--- HTTP 200 " + host + " \\([0-9]+ms\\)");
    assertThat(logMessages.get(4)).isEqualTo("Content-Type: application/json");
    assertThat(logMessages.get(5)).isEqualTo("Content-Length: 2");
    assertThat(logMessages.get(6)).isEqualTo("<--- END HTTP (2-byte body)");
  }

  @Test public void logRequestResponseHeadersAndArgs() throws Exception {
    restAdapter.setLogLevel(HEADERS_AND_ARGS);
    mockWebServer.enqueue(new MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody("Hi"));

    example.something();
    assertThat(logMessages).hasSize(9);
    assertThat(logMessages.get(0)).isEqualTo("---> HTTP GET " + host);
    assertThat(logMessages.get(1)).isEqualTo("Foo: Bar");
    assertThat(logMessages.get(2)).isEqualTo("---> END HTTP (no body)");
    assertThat(logMessages.get(3)).matches("<--- HTTP 200 " + host + " \\([0-9]+ms\\)");
    assertThat(logMessages.get(4)).isEqualTo("Content-Type: application/json");
    assertThat(logMessages.get(5)).isEqualTo("Content-Length: 2");
    assertThat(logMessages.get(6)).isEqualTo("<--- END HTTP (2-byte body)");
    assertThat(logMessages.get(7)).isEqualTo("<--- BODY:");
    assertThat(logMessages.get(8)).isEqualTo("Hi");
  }

  @Test public void logSuccessfulRequestResponseFullWhenResponseBodyPresent() throws Exception {
    restAdapter.setLogLevel(FULL);
    mockWebServer.enqueue(new MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody("{}"));

    example.something(stringBody("Hi"));
    assertThat(logMessages).hasSize(13);
    assertThat(logMessages.get(0)).isEqualTo("---> HTTP POST " + host);
    assertThat(logMessages.get(1)).isEqualTo("Foo: Bar");
    assertThat(logMessages.get(2)).isEqualTo("Content-Type: text/plain; charset=UTF-8");
    assertThat(logMessages.get(3)).isEqualTo("Content-Length: 2");
    assertThat(logMessages.get(4)).isEqualTo("");
    assertThat(logMessages.get(5)).isEqualTo("Hi");
    assertThat(logMessages.get(6)).isEqualTo("---> END HTTP (2-byte body)");
    assertThat(logMessages.get(7)).matches("<--- HTTP 200 " + host + " \\([0-9]+ms\\)");
    assertThat(logMessages.get(8)).isEqualTo("Content-Type: application/json");
    assertThat(logMessages.get(9)).isEqualTo("Content-Length: 2");
    assertThat(logMessages.get(10)).isEqualTo("");
    assertThat(logMessages.get(11)).isEqualTo("{}");
    assertThat(logMessages.get(12)).isEqualTo("<--- END HTTP (2-byte body)");
  }

  @Test public void logSuccessfulRequestResponseHeadersAndArgsWhenResponseBodyPresent() throws Exception {
    restAdapter.setLogLevel(HEADERS_AND_ARGS);
    mockWebServer.enqueue(new MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody("{}"));

    example.something(stringBody("Hi"));
    assertThat(logMessages).hasSize(13);
    assertThat(logMessages.get(0)).isEqualTo("---> HTTP POST " + host);
    assertThat(logMessages.get(1)).isEqualTo("Foo: Bar");
    assertThat(logMessages.get(2)).isEqualTo("Content-Type: text/plain; charset=UTF-8");
    assertThat(logMessages.get(3)).isEqualTo("Content-Length: 2");
    assertThat(logMessages.get(4)).isEqualTo("---> REQUEST:");
    assertThat(logMessages.get(5)).isEqualTo("#0: TypedString[Hi]");
    assertThat(logMessages.get(6)).isEqualTo("---> END HTTP (2-byte body)");
    assertThat(logMessages.get(7)).matches("<--- HTTP 200 " + host + " \\([0-9]+ms\\)");
    assertThat(logMessages.get(8)).isEqualTo("Content-Type: application/json");
    assertThat(logMessages.get(9)).isEqualTo("Content-Length: 2");
    assertThat(logMessages.get(10)).isEqualTo("<--- END HTTP (2-byte body)");
    assertThat(logMessages.get(11)).isEqualTo("<--- BODY:");
    assertThat(logMessages.get(12)).isEqualTo("{}");
  }

  @Test public void logSuccessfulRequestResponseFullWhenResponseBodyAbsent() throws Exception {
    restAdapter.setLogLevel(FULL);
    mockWebServer.enqueue(new MockResponse()
        .addHeader("Content-Type", "application/json")
        .addHeader("Content-Length", "0"));

    example.something();
    assertThat(logMessages).hasSize(7);
    assertThat(logMessages.get(0)).isEqualTo("---> HTTP GET " + host);
    assertThat(logMessages.get(1)).isEqualTo("Foo: Bar");
    assertThat(logMessages.get(2)).isEqualTo("---> END HTTP (no body)");
    assertThat(logMessages.get(3)).matches("<--- HTTP 200 " + host + " \\([0-9]+ms\\)");
    assertThat(logMessages.get(4)).isEqualTo("Content-Type: application/json");
    assertThat(logMessages.get(5)).isEqualTo("Content-Length: 0");
    assertThat(logMessages.get(6)).isEqualTo("<--- END HTTP (0-byte body)");
  }

  @Test public void logSuccessfulRequestResponseHeadersAndArgsWhenResponseBodyAbsent() throws Exception {
    restAdapter.setLogLevel(HEADERS_AND_ARGS);
    mockWebServer.enqueue(new MockResponse()
        .addHeader("Content-Type", "application/json"));
    // TODO why does adding a Content-Length: 0 header cause a double?

    example.something(stringBody("Hi"));
    assertThat(logMessages).hasSize(16); // TODO
    assertThat(logMessages.get(0)).isEqualTo("---> HTTP POST " + host);
    assertThat(logMessages.get(1)).isEqualTo("Foo: Bar");
    assertThat(logMessages.get(2)).isEqualTo("Content-Type: text/plain; charset=UTF-8");
    assertThat(logMessages.get(3)).isEqualTo("Content-Length: 2");
    assertThat(logMessages.get(4)).isEqualTo("---> REQUEST:");
    assertThat(logMessages.get(5)).isEqualTo("#0: String[Hi]");
    assertThat(logMessages.get(6)).isEqualTo("---> END HTTP (2-byte body)");
    assertThat(logMessages.get(7)).matches("<--- HTTP 200 " + host + " \\([0-9]+ms\\)");
    assertThat(logMessages.get(8)).isEqualTo("Content-Length: 0");
    assertThat(logMessages.get(9)).isEqualTo("Content-Type: application/json");
    assertThat(logMessages.get(10)).isEqualTo("<--- END HTTP (0-byte body)");
  }

  @Test public void successfulRequestResponseWhenMimeTypeMissing() throws Exception {
    mockWebServer.enqueue(new MockResponse().setBody("{}"));

    example.something();
  }

  @Test public void logSuccessfulRequestResponseFullWhenMimeTypeMissing() throws Exception {
    restAdapter.setLogLevel(FULL);
    mockWebServer.enqueue(new MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody("{}"));

    example.something();
    assertThat(logMessages).hasSize(9);
    assertThat(logMessages.get(0)).isEqualTo("---> HTTP GET " + host);
    assertThat(logMessages.get(1)).isEqualTo("Foo: Bar");
    assertThat(logMessages.get(2)).isEqualTo("---> END HTTP (no body)");
    assertThat(logMessages.get(3)).matches("<--- HTTP 200 " + host + " \\([0-9]+ms\\)");
    assertThat(logMessages.get(4)).isEqualTo("Content-Type: application/json");
    assertThat(logMessages.get(5)).isEqualTo("Content-Length: 2");
    assertThat(logMessages.get(6)).isEqualTo("");
    assertThat(logMessages.get(7)).isEqualTo("Hi");
    assertThat(logMessages.get(8)).isEqualTo("<--- END HTTP (2-byte body)");
  }

  @Test public void synchronousDoesNotUseExecutors() throws Exception {
    mockWebServer.enqueue(new MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody("{}"));

    example.something();

    verifyZeroInteractions(mockRequestExecutor);
    verifyZeroInteractions(mockCallbackExecutor);
  }

  @Test public void asynchronousUsesExecutors() throws Exception {
    mockWebServer.enqueue(new MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody("{}"));

    Callback<String> callback = mock(Callback.class);
    example.something(callback);

    verify(mockRequestExecutor).execute(any(CallbackRunnable.class));
    verify(mockCallbackExecutor).execute(any(Runnable.class));
    verify(callback).success(eq("Hey"), any(Response.class));
  }

  @Test public void malformedResponseThrowsConversionException() throws Exception {
    mockWebServer.enqueue(new MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody("{"));

    try {
      example.something();
      fail("RetrofitError expected on malformed response body.");
    } catch (RetrofitError e) {
      assertThat(e.getKind()).isEqualTo(RetrofitError.Kind.CONVERSION);
      assertThat(e.getResponse().code()).isEqualTo(200);
      assertThat(e.getCause()).isInstanceOf(ConversionException.class);
      assertThat(e.getResponse().body()).isNull();
    }
  }

  @Test public void errorResponseThrowsHttpError() throws Exception {
    mockWebServer.enqueue(new MockResponse().setResponseCode(500));

    try {
      example.something();
      fail("RetrofitError expected on non-2XX response code.");
    } catch (RetrofitError e) {
      assertThat(e.getKind()).isEqualTo(RetrofitError.Kind.HTTP);
      assertThat(e.getResponse().code()).isEqualTo(500);
      assertThat(e.getSuccessType()).isEqualTo(Object.class);
    }
  }

  @Test public void logErrorRequestResponseFullWhenMimeTypeMissing() throws Exception {
    restAdapter.setLogLevel(FULL);
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(403)
        .addHeader("Content-Type", "application/json")
        .setBody("Hi"));

    try {
      example.something();
      fail("RetrofitError expected on non-2XX response code.");
    } catch (RetrofitError e) {
      assertThat(e.getResponse().code()).isEqualTo(403);
    }

    assertThat(logMessages).hasSize(9);
    assertThat(logMessages.get(0)).isEqualTo("---> HTTP GET " + host);
    assertThat(logMessages.get(1)).isEqualTo("Foo: Bar");
    assertThat(logMessages.get(2)).isEqualTo("---> END HTTP (no body)");
    assertThat(logMessages.get(3)).matches("<--- HTTP 403 " + host + " \\([0-9]+ms\\)");
    assertThat(logMessages.get(4)).isEqualTo("Content-Type: application/json");
    assertThat(logMessages.get(5)).isEqualTo("Content-Length: 2");
    assertThat(logMessages.get(6)).isEqualTo("");
    assertThat(logMessages.get(7)).isEqualTo("Hi");
    assertThat(logMessages.get(8)).isEqualTo("<--- END HTTP (2-byte body)");
  }

  @Test public void logErrorRequestResponseFullWhenResponseBodyAbsent() throws Exception {
    restAdapter.setLogLevel(FULL);
    mockWebServer.enqueue(new MockResponse().setResponseCode(500)
        .addHeader("Content-Type", "application/json")
        .addHeader("Content-Length", "0"));

    try {
      example.something();
      fail("RetrofitError expected on non-2XX response code.");
    } catch (RetrofitError e) {
      assertThat(e.getResponse().code()).isEqualTo(500);
    }

    assertThat(logMessages).hasSize(7);
    assertThat(logMessages.get(0)).isEqualTo("---> HTTP GET " + host);
    assertThat(logMessages.get(1)).isEqualTo("Foo: Bar");
    assertThat(logMessages.get(2)).isEqualTo("---> END HTTP (no body)");
    assertThat(logMessages.get(3)).matches("<--- HTTP 500 " + host + " \\([0-9]+ms\\)");
    assertThat(logMessages.get(4)).isEqualTo("Content-Type: application/json");
    assertThat(logMessages.get(5)).isEqualTo("Content-Length: 0");
    assertThat(logMessages.get(6)).isEqualTo("<--- END HTTP (0-byte body)");
  }

  @Test public void clientExceptionThrowsNetworkError() throws Exception {
    mockWebServer.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));

    try {
      example.something();
      fail("RetrofitError expected when client throws exception.");
    } catch (RetrofitError e) {
      assertThat(e.getKind()).isEqualTo(RetrofitError.Kind.NETWORK);
      assertThat(e.getCause()).isInstanceOf(IOException.class);
    }
  }

  // TODO
  //@Test public void bodyTypedInputExceptionThrowsNetworkError() throws Exception {
  //  TypedInput body = spy(stringBody("{}"));
  //  InputStream bodyStream = mock(InputStream.class, new Answer() {
  //    @Override public Object answer(InvocationOnMock invocation) throws Throwable {
  //      throw new IOException("I'm broken!");
  //    }
  //  });
  //  doReturn(bodyStream).when(body).in();
  //
  //  when(mockClient.execute(any(Request.class))) //
  //      .thenReturn(new Response("http://example.com/", 200, "OK", NO_HEADERS, body));
  //
  //  try {
  //    example.something();
  //    fail("RetrofitError expected on malformed response body.");
  //  } catch (RetrofitError e) {
  //    assertThat(e.getKind()).isEqualTo(RetrofitError.Kind.NETWORK);
  //    assertThat(e.getCause()).isInstanceOf(IOException.class);
  //    assertThat(e.getCause()).hasMessage("I'm broken!");
  //  }
  //}

  // TODO
  //@Test public void unexpectedExceptionThrows() throws IOException {
  //  RuntimeException exception = new RuntimeException("More breakage.");
  //  when(mockClient.execute(any(Request.class))).thenThrow(exception);
  //
  //  try {
  //    example.something();
  //    fail("RetrofitError expected when unexpected exception thrown.");
  //  } catch (RetrofitError e) {
  //    assertThat(e.getKind()).isEqualTo(RetrofitError.Kind.UNEXPECTED);
  //    assertThat(e.getCause()).isSameAs(exception);
  //  }
  //}

  // TODO
  //@Test public void getResponseDirectly() throws Exception {
  //  Response response = new Response("http://example.com/", 200, "OK", NO_HEADERS, null);
  //  when(mockClient.execute(any(Request.class))) //
  //      .thenReturn(response);
  //  assertThat(example.direct()).isSameAs(response);
  //}

  // TODO
  //@Test public void streamingResponse() throws Exception {
  //  final InputStream is = new ByteArrayInputStream("Hey".getBytes("UTF-8"));
  //  TypedInput in = new TypedInput() {
  //    @Override public String mimeType() {
  //      return "text/string";
  //    }
  //
  //    @Override public long length() {
  //      return 3;
  //    }
  //
  //    @Override public InputStream in() throws IOException {
  //      return is;
  //    }
  //  };
  //
  //  when(mockClient.execute(any(Request.class))) //
  //      .thenReturn(new Response("http://example.com/", 200, "OK", NO_HEADERS, in));
  //
  //  Response response = example.streaming();
  //  assertThat(response.getBody().in()).isSameAs(is);
  //}

  // TODO
  //@Test public void closeInputStream() throws IOException {
  //  // Set logger and profiler on example to make sure we exercise all the code paths.
  //  Example example = new RestAdapter.Builder() //
  //      .setClient(mockClient)
  //      .setExecutors(mockRequestExecutor, mockCallbackExecutor)
  //      .setEndpoint("http://example.com")
  //      .setLog(RestAdapter.Log.NONE)
  //      .setLogLevel(FULL)
  //      .build()
  //      .create(Example.class);
  //
  //  ByteArrayInputStream is = spy(new ByteArrayInputStream("hello".getBytes()));
  //  TypedInput typedInput = mock(TypedInput.class);
  //  when(typedInput.in()).thenReturn(is);
  //  Response response = new Response("http://example.com/", 200, "OK", NO_HEADERS, typedInput);
  //  when(mockClient.execute(any(Request.class))) //
  //      .thenReturn(response);
  //  example.something();
  //  verify(is).close();
  //}

  @Test public void getResponseDirectlyAsync() throws Exception {
    mockWebServer.enqueue(new MockResponse());
    Callback<ResponseBody> callback = mock(Callback.class);

    example.direct(callback);

    verify(mockRequestExecutor).execute(any(CallbackRunnable.class));
    verify(mockCallbackExecutor).execute(any(Runnable.class));
    verify(callback).success(any(ResponseBody.class), any(Response.class));
  }

  @Test public void getAsync() throws Exception {
    mockWebServer.enqueue(new MockResponse().setBody("Hey"));
    Callback<String> callback = mock(Callback.class);

    example.something(callback);

    verify(mockRequestExecutor).execute(any(CallbackRunnable.class));
    verify(mockCallbackExecutor).execute(any(Runnable.class));

    ArgumentCaptor<String> responseCaptor = ArgumentCaptor.forClass(String.class);
    verify(callback).success(responseCaptor.capture(), any(Response.class));
    assertThat(responseCaptor.getValue()).isEqualTo("Hey");
  }


  @Test public void errorAsync() throws Exception {
    mockWebServer.enqueue(new MockResponse()
        .setStatus("HTTP/1.1 500 Broken!")
        .setBody("Hey"));

    Callback<String> callback = mock(Callback.class);
    example.something(callback);

    verify(mockRequestExecutor).execute(any(CallbackRunnable.class));
    verify(mockCallbackExecutor).execute(any(Runnable.class));

    ArgumentCaptor<RetrofitError> errorCaptor = ArgumentCaptor.forClass(RetrofitError.class);
    verify(callback).failure(errorCaptor.capture());
    RetrofitError error = errorCaptor.getValue();
    assertThat(error.getResponse().code()).isEqualTo(500);
    assertThat(error.getResponse().message()).isEqualTo("Broken!");
    assertThat(error.getSuccessType()).isEqualTo(String.class);
    assertThat(error.getBody()).isEqualTo("Hey");
  }

  @Test public void observableCallsOnNext() throws Exception {
    mockWebServer.enqueue(new MockResponse());
    Action1<String> action = mock(Action1.class);
    example.observable("Howdy").subscribe(action);
    verify(action).call(eq("hello"));
  }

  @Test public void observableCallsOnError() throws Exception {
    mockWebServer.enqueue(new MockResponse().setResponseCode(300));
    Action1<String> onSuccess = mock(Action1.class);
    Action1<Throwable> onError = mock(Action1.class);
    example.observable("Howdy").subscribe(onSuccess, onError);
    verifyZeroInteractions(onSuccess);

    ArgumentCaptor<RetrofitError> errorCaptor = ArgumentCaptor.forClass(RetrofitError.class);
    verify(onError).call(errorCaptor.capture());
    RetrofitError value = errorCaptor.getValue();
    assertThat(value.getSuccessType()).isEqualTo(String.class);
  }

  // TODO
  //@Test public void observableHandlesParams() throws Exception {
  //  ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
  //  when(mockClient.execute(requestCaptor.capture())) //
  //      .thenReturn(new Response("http://example.com/", 200, "OK", NO_HEADERS, stringBody("hello")));
  //  ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
  //  Action1<Response> action = mock(Action1.class);
  //  example.observable("X", "Y").subscribe(action);
  //
  //  Request request = requestCaptor.getValue();
  //  assertThat(request.getUrl()).contains("/X/Y");
  //
  //  verify(action).call(responseCaptor.capture());
  //  Response response = responseCaptor.getValue();
  //  assertThat(response.code()).isEqualTo(200);
  //}

  @Test public void observableUsesHttpExecutor() throws IOException {
    mockWebServer.enqueue(new MockResponse());
    example.observable("Howdy").subscribe(mock(Action1.class));

    verify(mockRequestExecutor, atLeastOnce()).execute(any(Runnable.class));
    verifyZeroInteractions(mockCallbackExecutor);
  }
}
