// Copyright 2012 Square, Inc.
package retrofit.http;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import retrofit.http.client.Client;
import retrofit.http.client.Request;
import retrofit.http.client.Response;
import retrofit.http.mime.TypedInput;

import static retrofit.http.Utils.SynchronousExecutor;

/**
 * Converts Java method calls to Rest calls.
 *
 * @author Bob Lee (bob@squareup.com)
 * @author Jake Wharton (jw@squareup.com)
 */
public class RestAdapter {
  private static final Logger LOGGER = Logger.getLogger(RestAdapter.class.getName());
  private static final int LOG_CHUNK_SIZE = 4000;
  private static final String SUFFIX = "$$RetrofitImpl";
  static final String THREAD_PREFIX = "Retrofit-";

  private final Server server;
  private final Client.Provider clientProvider;
  private final Executor httpExecutor;
  private final Executor callbackExecutor;
  private final Headers headers;
  private final Converter converter;
  private final Profiler profiler;

  private RestAdapter(Server server, Client.Provider clientProvider, Executor httpExecutor,
      Executor callbackExecutor, Headers headers, Converter converter, Profiler profiler) {
    this.server = server;
    this.clientProvider = clientProvider;
    this.httpExecutor = httpExecutor;
    this.callbackExecutor = callbackExecutor;
    this.headers = headers;
    this.converter = converter;
    this.profiler = profiler;
  }

  /**
   * Adapts a Java interface to a REST API.
   * <p>
   * The relative path for a given method is obtained from an annotation on the method describing
   * the request type. The names of URL parameters are retrieved from {@link Name}
   * annotations on the method parameters.
   * <p>
   * HTTP requests happen in one of two ways:
   * <ul>
   * <li>On the provided HTTP {@link Executor} with callbacks marshaled to the callback
   * {@link Executor}. The last method parameter should be of type {@link Callback}. The HTTP
   * response will be converted to the callback's parameter type using the specified
   * {@link Converter}. If the callback parameter type uses a wildcard, the lower bound will be
   * used as the conversion type.</li>
   * <li>On the current thread returning the response or throwing a {@link RetrofitError}. The HTTP
   * response will be converted to the method's return type using the specified
   * {@link Converter}.</li>
   * </ul>
   * <p>
   * For example:
   * <pre>
   *   public interface MyApi {
   *     &#64;POST("go") // Asynchronous execution.
   *     void go(@Name("a") String a, @Name("b") int b, Callback&lt;? super MyResult> callback);
   *     &#64;POST("go") // Synchronous execution.
   *     MyResult go(@Name("a") String a, @Name("b") int b);
   *   }
   * </pre>
   *
   * @param type to implement
   */
  @SuppressWarnings("unchecked")
  public <T> T create(Class<T> type) {
    if (!type.isInterface()) {
      throw new IllegalArgumentException("Only interface endpoint definitions are supported.");
    }
    String name = type.getName();
    try {
      Class<?> impl = Class.forName(name + SUFFIX);
      Constructor<?> constructor = impl.getConstructor(RestAdapter.class);
      return (T) constructor.newInstance(this);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Unable to load generated implementation for " + name, e);
    }
  }

  public abstract static class RestHandler {
    protected final Server server;
    protected final Converter converter;
    protected final Headers headers;
    protected final Profiler profiler;
    private final Client.Provider client;
    private final Executor httpExecutor;
    private final Executor callbackExecutor;

    protected RestHandler(RestAdapter restAdapter) {
      server = restAdapter.server;
      converter = restAdapter.converter;
      headers = restAdapter.headers;
      profiler = restAdapter.profiler;
      client = restAdapter.clientProvider;
      httpExecutor = restAdapter.httpExecutor;
      callbackExecutor = restAdapter.callbackExecutor;
    }

    protected ResponseWrapper execute(Request request) {
      try {
        //Object profilerObject = null;
        //if (profiler != null) {
        //  profilerObject = profiler.beforeCall();
        //}

        long time = System.nanoTime();
        Response response = client.get().execute(request);
        time = (System.nanoTime() - time) / 1000000;

        int statusCode = response.getStatus();
        //if (profiler != null) {
        //Profiler.RequestInformation requestInfo = getRequestInfo(server, methodDetails, request);
        //  profiler.afterCall(requestInfo, time, statusCode, profilerObject);
        //}

        TypedInput body = response.getBody();
        //if (LOGGER.isLoggable(Level.FINE)) {
        //  //Replace the response since the logger needs to consume the entire input stream.
        //  body = logResponse(request.getUrl(), response.getStatus(), body, elapsedTime);
        //}

        Type type = null; //methodDetails.type;
        if (statusCode >= 200 && statusCode < 300) { // 2XX == successful request
          //if (type.equals(Response.class)) {
            return null; // TODO response;
          //}
          //if (body == null) {
          //  return null;
          //}
          //try {
          //  return converter.fromBody(body, type);
          //} catch (ConversionException e) {
          //  throw RetrofitError.conversionError(request.getUrl(), response, converter, type, e);
          //}
        }
        throw RetrofitError.httpError(request.getUrl(), response, converter, type);
      } catch (RetrofitError e) {
        throw e; // Pass through our own errors.
      } catch (IOException e) {
        throw RetrofitError.networkError(request.getUrl(), e);
      } catch (Throwable t) {
        throw RetrofitError.unexpectedError(request.getUrl(), t);
      }
    }

    protected <T> void asyncExecute(final Request request, Callback<T> callback) {
      httpExecutor.execute(new CallbackRunnable(callback, callbackExecutor) {
        @Override public ResponseWrapper obtainResponse() {
          return execute(request);
        }
      });
    }

    protected static String urlEncode(Object value) {
      String string = String.valueOf(value);
      try {
        return URLEncoder.encode(string, "UTF-8");
      } catch (UnsupportedEncodingException e) {
        throw new RuntimeException("Unable to URL encode: " + string);
      }
    }
  }

  /**
   * Build a new {@link RestAdapter}.
   * <p>
   * Calling the following methods is required before calling {@link #build()}:
   * <ul>
   * <li>{@link #setServer(Server)}</li>
   * <li>{@link #setClient(Client.Provider)}</li>
   * <li>{@link #setConverter(Converter)}</li>
   * </ul>
   * <p>
   * If you are using asynchronous execution (i.e., with {@link Callback Callbacks}) the following
   * is also required:
   * <ul>
   * <li>{@link #setExecutors(java.util.concurrent.Executor, java.util.concurrent.Executor)}</li>
   * </ul>
   */
  public static class Builder {
    private Server server;
    private Client.Provider clientProvider;
    private Executor httpExecutor;
    private Executor callbackExecutor;
    private Headers headers;
    private Converter converter;
    private Profiler profiler;

    public Builder setServer(String endpoint) {
      if (endpoint == null) throw new NullPointerException("endpoint");
      return setServer(new Server(endpoint));
    }

    public Builder setServer(Server server) {
      if (server == null) throw new NullPointerException("server");
      this.server = server;
      return this;
    }

    public Builder setClient(final Client client) {
      if (client == null) throw new NullPointerException("client");
      return setClient(new Client.Provider() {
        @Override public Client get() {
          return client;
        }
      });
    }

    public Builder setClient(Client.Provider clientProvider) {
      if (clientProvider == null) throw new NullPointerException("clientProvider");
      this.clientProvider = clientProvider;
      return this;
    }

    /**
     * Executors used for asynchronous HTTP client downloads and callbacks.
     *
     * @param httpExecutor Executor on which HTTP client calls will be made.
     * @param callbackExecutor Executor on which any {@link Callback} methods will be invoked. If
     * this argument is {@code null} then callback methods will be run on the same thread as the
     * HTTP client.
     */
    public Builder setExecutors(Executor httpExecutor, Executor callbackExecutor) {
      if (httpExecutor == null) throw new NullPointerException("httpExecutor");
      if (callbackExecutor == null) callbackExecutor = new SynchronousExecutor();
      this.httpExecutor = httpExecutor;
      this.callbackExecutor = callbackExecutor;
      return this;
    }

    public Builder setHeaders(Headers headers) {
      if (headers == null) throw new NullPointerException("headers");
      this.headers = headers;
      return this;
    }

    public Builder setConverter(Converter converter) {
      if (converter == null) throw new NullPointerException("converter");
      this.converter = converter;
      return this;
    }

    public Builder setProfiler(Profiler profiler) {
      if (profiler == null) throw new NullPointerException("profiler");
      this.profiler = profiler;
      return this;
    }

    public RestAdapter build() {
      if (server == null) {
        throw new IllegalArgumentException("Server may not be null.");
      }
      ensureSaneDefaults();
      return new RestAdapter(server, clientProvider, httpExecutor, callbackExecutor,
          headers, converter, profiler);
    }

    private void ensureSaneDefaults() {
      if (converter == null) {
        converter = Platform.get().defaultConverter();
      }
      if (clientProvider == null) {
        clientProvider = Platform.get().defaultClient();
      }
      if (httpExecutor == null) {
        httpExecutor = Platform.get().defaultHttpExecutor();
      }
      if (callbackExecutor == null) {
        callbackExecutor = Platform.get().defaultCallbackExecutor();
      }
      if (headers == null) {
        headers = Headers.NONE;
      }
    }
  }
}
