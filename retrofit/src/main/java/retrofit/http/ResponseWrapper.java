// Copyright 2013 Square, Inc.
package retrofit.http;

import retrofit.http.client.Response;

/**
 * A wrapper that holds the {@link Response} and {@link Converter} response to be used by the
 * {@link CallbackRunnable} for success method calls on {@link Callback}.
 *
 * @author JJ Ford (jj.n.ford@gmail.com)
 */
final class ResponseWrapper {
  final Response response;
  final Object responseBody;
  final long invocationTime;

  ResponseWrapper(Response response, Object responseBody, long invocationTime) {
    this.response = response;
    this.responseBody = responseBody;
    this.invocationTime = invocationTime;
  }
}
