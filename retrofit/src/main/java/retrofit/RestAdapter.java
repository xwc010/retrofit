/*
 * Copyright (C) 2012 Square, Inc.
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
package retrofit;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import retrofit.client.Client;
import retrofit.client.Header;
import retrofit.client.Request;
import retrofit.client.Response;
import retrofit.converter.ConversionException;
import retrofit.converter.Converter;
import retrofit.mime.MimeUtil;
import retrofit.mime.TypedByteArray;
import retrofit.mime.TypedInput;
import retrofit.mime.TypedOutput;

/**
 * Adapts a Java interface to a REST API.
 * <p>
 * API endpoints are defined as methods on an interface with annotations providing metadata about
 * the form in which the HTTP call should be made.
 * <p>
 * The relative path for a given method is obtained from an annotation on the method describing
 * the request type. The built-in methods are {@link retrofit.http.GET GET},
 * {@link retrofit.http.PUT PUT}, {@link retrofit.http.POST POST}, {@link retrofit.http.HEAD HEAD},
 * and {@link retrofit.http.DELETE DELETE}. You can define your own HTTP method by creating an
 * annotation that takes a {code String} value and itself is annotated with
 * {@link retrofit.http.RestMethod @RestMethod}.
 * <p>
 * Method parameters can be used to replace parts of the URL by annotating them with
 * {@link retrofit.http.Path @Path}. Replacement sections are denoted by an identifier surrounded
 * by curly braces (e.g., "{foo}"). To add items to the query string of a URL use
 * {@link retrofit.http.Query @Query}.
 * <p>
 * HTTP requests happen in one of two ways:
 * <ul>
 * <li>On the provided HTTP {@link Executor} with callbacks marshaled to the callback
 * {@link Executor}. The last method parameter should be of type {@link Callback}. The HTTP
 * response will be converted to the callback's parameter type using the specified
 * {@link retrofit.converter.Converter Converter}. If the callback parameter type uses a wildcard,
 * the lower bound will be used as the conversion type.
 * <li>On the current thread returning the response or throwing a {@link RetrofitError}. The HTTP
 * response will be converted to the method's return type using the specified
 * {@link retrofit.converter.Converter Converter}.
 * </ul>
 * <p>
 * The body of a request is denoted by the {@link retrofit.http.Body @Body} annotation. The object
 * will be converted to request representation by a call to
 * {@link retrofit.converter.Converter#toBody(Object) toBody} on the supplied
 * {@link retrofit.converter.Converter Converter} for this instance. The body can also be a
 * {@link TypedOutput} where it will be used directly.
 * <p>
 * Alternative request body formats are supported by method annotations and corresponding parameter
 * annotations:
 * <ul>
 * <li>{@link retrofit.http.FormUrlEncoded @FormUrlEncoded} - Form-encoded data with key-value
 * pairs specified by the {@link retrofit.http.Field @Field} parameter annotation.
 * <li>{@link retrofit.http.Multipart @Multipart} - RFC 2387-compliant multi-part data with parts
 * specified by the {@link retrofit.http.Part @Part} parameter annotation.
 * </ul>
 * <p>
 * Additional static headers can be added for an endpoint using the
 * {@link retrofit.http.Headers @Headers} method annotation. For per-request control over a header
 * annotate a parameter with {@link Header @Header}.
 * <p>
 * For example:
 * <pre>
 * public interface MyApi {
 *   &#64;POST("/category/{cat}") // Asynchronous execution.
 *   void categoryList(@Path("cat") String a, @Query("page") int b,
 *                     Callback&lt;List&lt;Item&gt;&gt; cb);
 *   &#64;POST("/category/{cat}") // Synchronous execution.
 *   List&lt;Item&gt; categoryList(@Path("cat") String a, @Query("page") int b);
 * }
 * </pre>
 * <p>
 * Calling {@link #create(Class)} with {@code MyApi.class} will validate and create a new
 * implementation of the API.
 *
 * @author Bob Lee (bob@squareup.com)
 * @author Jake Wharton (jw@squareup.com)
 */
public class RestAdapter {
  /** Simple logging abstraction for debug messages. */
  public interface Log {
    /** Log a debug message to the appropriate console. */
    void log(String message);
  }

  /** Controls the level of logging. */
  public enum LogLevel {
    /** No logging. */
    NONE,
    /** Log only the request method and URL and the response status code and execution time. */
    BASIC,
    /** Log the basic information along with request and response headers. */
    HEADERS,
    /** Log the basic information along with request and response objects via toString(). */
    HEADERS_AND_ARGS,
    /**
     * Log the headers, body, and metadata for both requests and responses.
     * <p>
     * Note: This requires that the entire request and response body be buffered in memory!
     */
    FULL;

    public boolean log() {
      return this != NONE;
    }
  }

  private final Map<Class<?>, Map<Method, RestMethodInfo>> serviceMethodInfoCache =
      new LinkedHashMap<Class<?>, Map<Method, RestMethodInfo>>();

  final Endpoint server;
  final Executor callbackExecutor;
  final RequestInterceptor requestInterceptor;
  final Converter converter;
  final Log log;
  final ErrorHandler errorHandler;

  private final Client client;
  private RxSupport rxSupport;

  volatile LogLevel logLevel;

  private RestAdapter(Endpoint server, Client client, Executor callbackExecutor,
      RequestInterceptor requestInterceptor, Converter converter, ErrorHandler errorHandler,
      Log log, LogLevel logLevel) {
    this.server = server;
    this.client = client;
    this.callbackExecutor = callbackExecutor;
    this.requestInterceptor = requestInterceptor;
    this.converter = converter;
    this.errorHandler = errorHandler;
    this.log = log;
    this.logLevel = logLevel;
  }

  /** Change the level of logging. */
  public void setLogLevel(LogLevel loglevel) {
    if (logLevel == null) {
      throw new NullPointerException("Log level may not be null.");
    }
    this.logLevel = loglevel;
  }

  /** The current logging level. */
  public LogLevel getLogLevel() {
    return logLevel;
  }

  /** Create an implementation of the API defined by the specified {@code service} interface. */
  @SuppressWarnings("unchecked")
  public <T> T create(Class<T> service) {
    Utils.validateServiceClass(service);
    return (T) Proxy.newProxyInstance(service.getClassLoader(), new Class<?>[] { service },
        new RestHandler(getMethodInfoCache(service)));
  }

  Map<Method, RestMethodInfo> getMethodInfoCache(Class<?> service) {
    synchronized (serviceMethodInfoCache) {
      Map<Method, RestMethodInfo> methodInfoCache = serviceMethodInfoCache.get(service);
      if (methodInfoCache == null) {
        methodInfoCache = new LinkedHashMap<Method, RestMethodInfo>();
        serviceMethodInfoCache.put(service, methodInfoCache);
      }
      return methodInfoCache;
    }
  }

  static RestMethodInfo getMethodInfo(Map<Method, RestMethodInfo> cache, Method method) {
    synchronized (cache) {
      RestMethodInfo methodInfo = cache.get(method);
      if (methodInfo == null) {
        methodInfo = new RestMethodInfo(method);
        cache.put(method, methodInfo);
      }
      return methodInfo;
    }
  }

  private class RestHandler implements InvocationHandler {
    private final Map<Method, RestMethodInfo> methodDetailsCache;

    RestHandler(Map<Method, RestMethodInfo> methodDetailsCache) {
      this.methodDetailsCache = methodDetailsCache;
    }

    @SuppressWarnings("unchecked") //
    @Override public Object invoke(Object proxy, Method method, final Object[] args)
        throws Throwable {
      // If the method is a method from Object then defer to normal invocation.
      if (method.getDeclaringClass() == Object.class) {
        return method.invoke(this, args);
      }

      RestMethodInfo methodInfo = getMethodInfo(methodDetailsCache, method);
      Request request = createRequest(methodInfo, args);
      switch (methodInfo.executionType) {
        case SYNC:
          return invokeSync(methodInfo, request);
        case ASYNC:
          invokeAsync(methodInfo, request, (Callback) args[args.length - 1]);
          return null; // Async has void return type.
        case RX:
          return invokeRx(methodInfo, request);
        default:
          throw new IllegalStateException("Unknown response type: " + methodInfo.executionType);
      }
    }

    private Object invokeSync(RestMethodInfo methodInfo, Request request) throws Throwable {
      final CountDownLatch latch = new CountDownLatch(1);
      final AtomicReference<Object> result = new AtomicReference<Object>();
      final AtomicReference<RetrofitError> error = new AtomicReference<RetrofitError>();
      invokeAsync(methodInfo, request, new Callback() {
        @Override public void success(Object o, Response response) {
          result.set(o);
          latch.countDown();
        }

        @Override public void failure(RetrofitError e) {
          error.set(e);
          latch.countDown();
        }
      });
      latch.await();

      RetrofitError actualError = error.get();
      if (actualError != null) {
        throw handleError(actualError);
      }
      return result.get();
    }

    private Throwable handleError(RetrofitError actualError) {
      Throwable throwable = errorHandler.handleError(actualError);
      if (throwable == null) {
        return new IllegalStateException("Error handler returned null for wrapped exception.");
      }
      return throwable;
    }

    private void invokeAsync(final RestMethodInfo methodInfo, final Request request,
        final Callback callback) {
      Client.AsyncCallback async = new Client.AsyncCallback() {
        @Override public void onResponse(Response response) {
          try {
            handleAsyncResponse(methodInfo, request, response, callback);
          } catch (RetrofitError e) {
            Throwable throwable = errorHandler.handleError(e);
            if (throwable != e) {
              e = RetrofitError.unexpectedError(request.getUrl(), throwable);
            }
            callFailure(callback, e);
          } catch (IOException e) {
            callFailure(callback, RetrofitError.networkError(request.getUrl(), e));
          } catch (Throwable t) {
            callFailure(callback, RetrofitError.unexpectedError(request.getUrl(), t));
          }
        }

        @Override public void onFailure(IOException e) {
          callFailure(callback, RetrofitError.networkError(request.getUrl(), e));
        }
      };
      try {
        client.execute(request, async);
      } catch (RuntimeException e) {
        callFailure(callback, RetrofitError.unexpectedError(request.getUrl(), e));
      }
    }

    private Object invokeRx(final RestMethodInfo methodInfo, final Request request) {
      if (rxSupport == null) {
        if (Platform.HAS_RX_JAVA) {
          rxSupport = new RxSupport();
        } else {
          throw new IllegalStateException("Found Observable return type but RxJava not present.");
        }
      }
      return rxSupport.createRequestObservable(new RxSupport.Invoker() {
        @Override public void invoke(final RxSupport.Callback callback) {
          invokeAsync(methodInfo, request, new Callback() {
            @Override public void success(Object o, Response response) {
              callback.result(o);
            }

            @Override public void failure(RetrofitError error) {
              callback.error(handleError(error));
            }
          });
        }
      });
    }

    private void handleAsyncResponse(RestMethodInfo methodInfo, Request request, Response response,
        Callback callback) throws IOException {
      String url = request.getUrl();

      if (logLevel.log()) {
        response = logAndReplaceResponse(url, response);
      }

      Type type = methodInfo.responseObjectType;

      int statusCode = response.getStatus();
      if (statusCode < 200 || statusCode >= 300) {
        response = Utils.readBodyToBytesIfNecessary(response);
        throw RetrofitError.httpError(url, response, converter, type);
      }

      // Caller requested the raw Response object directly.
      if (type.equals(Response.class)) {
        if (!methodInfo.isStreaming) {
          // Read the entire stream and replace with one backed by a byte[].
          response = Utils.readBodyToBytesIfNecessary(response);
        }
        callResponse(callback, response, response);
      } else {
        handleAsyncResponseBody(request, response, type, callback);
      }
    }

    private void handleAsyncResponseBody(Request request, Response response, Type type,
        Callback callback) throws IOException {
      TypedInput body = response.getBody();
      if (body == null) {
        callResponse(callback, null, response);
        return;
      }

      ExceptionCatchingTypedInput wrapped = new ExceptionCatchingTypedInput(body);
      try {
        Object convert = converter.fromBody(wrapped, type);
        logResponseBody(convert);
        callResponse(callback, convert, response);
      } catch (ConversionException e) {
        // If the underlying input stream threw an exception, propagate that rather than
        // indicating that it was a conversion exception.
        if (wrapped.threwException()) {
          throw wrapped.getThrownException();
        }

        // The response body was partially read by the converter. Replace it with null.
        response = Utils.replaceResponseBody(response, null);

        throw RetrofitError.conversionError(request.getUrl(), response, converter, type, e);
      }
    }

    private void callResponse(final Callback callback, final Object result,
        final Response response) {
      callbackExecutor.execute(new Runnable() {
        @Override public void run() {
          callback.success(result, response);
        }
      });
    }

    private void callFailure(final Callback callback, final RetrofitError error) {
      callbackExecutor.execute(new Runnable() {
        @Override public void run() {
          callback.failure(error);
        }
      });
    }

    private Request createRequest(RestMethodInfo methodInfo, Object[] args) {
      String serverUrl = server.getUrl();
      RequestBuilder requestBuilder = new RequestBuilder(serverUrl, methodInfo, converter);
      requestBuilder.setArguments(args);

      requestInterceptor.intercept(requestBuilder);

      Request request = requestBuilder.build();

      if (logLevel.log()) {
        try {
          request = logAndReplaceRequest("HTTP", request, args);
        } catch (IOException e) {
          throw new RuntimeException("Unable to buffer request while logging.", e);
        }
      }

      return request;
    }
  }

  /** Log request headers and body. Consumes request body and returns identical replacement. */
  Request logAndReplaceRequest(String name, Request request, Object[] args) throws IOException {
    log.log(String.format("---> %s %s %s", name, request.getMethod(), request.getUrl()));

    if (logLevel.ordinal() >= LogLevel.HEADERS.ordinal()) {
      for (Header header : request.getHeaders()) {
        log.log(header.toString());
      }

      String bodySize = "no";
      TypedOutput body = request.getBody();
      if (body != null) {
        String bodyMime = body.mimeType();
        if (bodyMime != null) {
          log.log("Content-Type: " + bodyMime);
        }

        long bodyLength = body.length();
        bodySize = bodyLength + "-byte";
        if (bodyLength != -1) {
          log.log("Content-Length: " + bodyLength);
        }

        if (logLevel.ordinal() >= LogLevel.FULL.ordinal()) {
          if (!request.getHeaders().isEmpty()) {
            log.log("");
          }
          if (!(body instanceof TypedByteArray)) {
            // Read the entire response body to we can log it and replace the original response
            request = Utils.readBodyToBytesIfNecessary(request);
            body = request.getBody();
          }

          byte[] bodyBytes = ((TypedByteArray) body).getBytes();
          String bodyCharset = MimeUtil.parseCharset(body.mimeType(), "UTF-8");
          log.log(new String(bodyBytes, bodyCharset));
        } else if (logLevel.ordinal() >= LogLevel.HEADERS_AND_ARGS.ordinal()) {
          if (!request.getHeaders().isEmpty()) {
            log.log("---> REQUEST:");
          }
          for (int i = 0; i < args.length; i++) {
            log.log("#" + i + ": " + args[i]);
          }
        }
      }

      log.log(String.format("---> END %s (%s body)", name, bodySize));
    }

    return request;
  }

  /** Log response headers and body. Consumes response body and returns identical replacement. */
  private Response logAndReplaceResponse(String url, Response response) throws IOException {
    log.log(String.format("<--- HTTP %s %s", response.getStatus(), url));

    if (logLevel.ordinal() >= LogLevel.HEADERS.ordinal()) {
      for (Header header : response.getHeaders()) {
        log.log(header.toString());
      }

      long bodySize = 0;
      TypedInput body = response.getBody();
      if (body != null) {
        bodySize = body.length();

        if (logLevel.ordinal() >= LogLevel.FULL.ordinal()) {
          if (!response.getHeaders().isEmpty()) {
            log.log("");
          }

          if (!(body instanceof TypedByteArray)) {
            // Read the entire response body so we can log it and replace the original response
            response = Utils.readBodyToBytesIfNecessary(response);
            body = response.getBody();
          }

          byte[] bodyBytes = ((TypedByteArray) body).getBytes();
          bodySize = bodyBytes.length;
          String bodyMime = body.mimeType();
          String bodyCharset = MimeUtil.parseCharset(bodyMime, "UTF-8");
          log.log(new String(bodyBytes, bodyCharset));
        }
      }

      log.log(String.format("<--- END HTTP (%s-byte body)", bodySize));
    }

    return response;
  }

  private void logResponseBody(Object body) {
    if (logLevel.ordinal() == LogLevel.HEADERS_AND_ARGS.ordinal()) {
      log.log("<--- BODY:");
      log.log(String.valueOf(body));
    }
  }

  /** Log an exception that occurred during the processing of a request or response. */
  void logException(Throwable t, String url) {
    log.log(String.format("---- ERROR %s", url != null ? url : ""));
    StringWriter sw = new StringWriter();
    t.printStackTrace(new PrintWriter(sw));
    log.log(sw.toString());
    log.log("---- END ERROR");
  }

  /**
   * Build a new {@link RestAdapter}.
   * <p>
   * Calling {@link #setEndpoint} is required before calling {@link #build()}. All other methods
   * are optional.
   */
  public static class Builder {
    private Endpoint endpoint;
    private Client client;
    private Executor callbackExecutor;
    private RequestInterceptor requestInterceptor;
    private Converter converter;
    private ErrorHandler errorHandler;
    private Log log;
    private LogLevel logLevel = LogLevel.NONE;

    /** API endpoint URL. */
    public Builder setEndpoint(String endpoint) {
       return setEndpoint(Endpoints.newFixedEndpoint(endpoint));
    }

    /** API endpoint. */
    public Builder setEndpoint(Endpoint endpoint) {
      if (endpoint == null) {
        throw new NullPointerException("Endpoint may not be null.");
      }
      this.endpoint = endpoint;
      return this;
    }

    /** The HTTP client used for requests. */
    public Builder setClient(Client client) {
      if (client == null) {
        throw new NullPointerException("Client may not be null.");
      }
      this.client = client;
      return this;
    }

    /**
     * Executor on which any {@link Callback} methods will be invoked. If this argument is
     * {@code null} then callback methods will be run on the same thread as the HTTP client.
     */
    public Builder setCallbackExecutor(Executor callbackExecutor) {
      if (callbackExecutor == null) {
        callbackExecutor = new Utils.SynchronousExecutor();
      }
      this.callbackExecutor = callbackExecutor;
      return this;
    }

    /** A request interceptor for adding data to every request. */
    public Builder setRequestInterceptor(RequestInterceptor requestInterceptor) {
      if (requestInterceptor == null) {
        throw new NullPointerException("Request interceptor may not be null.");
      }
      this.requestInterceptor = requestInterceptor;
      return this;
    }

    /** The converter used for serialization and deserialization of objects. */
    public Builder setConverter(Converter converter) {
      if (converter == null) {
        throw new NullPointerException("Converter may not be null.");
      }
      this.converter = converter;
      return this;
    }

    /**
     * The error handler allows you to customize the type of exception thrown for errors on
     * synchronous requests.
     */
    public Builder setErrorHandler(ErrorHandler errorHandler) {
      if (errorHandler == null) {
        throw new NullPointerException("Error handler may not be null.");
      }
      this.errorHandler = errorHandler;
      return this;
    }

    /** Configure debug logging mechanism. */
    public Builder setLog(Log log) {
      if (log == null) {
        throw new NullPointerException("Log may not be null.");
      }
      this.log = log;
      return this;
    }

    /** Change the level of logging. */
    public Builder setLogLevel(LogLevel logLevel) {
      if (logLevel == null) {
        throw new NullPointerException("Log level may not be null.");
      }
      this.logLevel = logLevel;
      return this;
    }

    /** Create the {@link RestAdapter} instances. */
    public RestAdapter build() {
      if (endpoint == null) {
        throw new IllegalArgumentException("Endpoint may not be null.");
      }
      ensureSaneDefaults();
      return new RestAdapter(endpoint, client, callbackExecutor,
          requestInterceptor, converter, errorHandler, log, logLevel);
    }

    private void ensureSaneDefaults() {
      if (converter == null) {
        converter = Platform.get().defaultConverter();
      }
      if (client == null) {
        client = Platform.get().defaultClient();
      }
      if (callbackExecutor == null) {
        callbackExecutor = Platform.get().defaultCallbackExecutor();
      }
      if (errorHandler == null) {
        errorHandler = ErrorHandler.DEFAULT;
      }
      if (log == null) {
        log = Platform.get().defaultLog();
      }
      if (requestInterceptor == null) {
        requestInterceptor = RequestInterceptor.NONE;
      }
    }
  }
}
