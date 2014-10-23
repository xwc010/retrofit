/*
 * Copyright (C) 2012 Square, Inc.
 * Copyright (C) 2007 The Guava Authors
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

import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseBody;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.Executor;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;

final class Utils {
  public static final Charset UTF8 = Charset.forName("UTF-8");

  static Request bufferBody(Request request, Buffer buffer) throws IOException {
    final RequestBody body = request.body();
    if (body == null) {
      return request;
    }

    body.writeTo(buffer);

    final Buffer clone = buffer.clone();
    return request.newBuilder()
        .method(request.method(), new RequestBody() {
          @Override public MediaType contentType() {
            return body.contentType();
          }
          @Override public long contentLength() throws IOException {
            return body.contentLength();
          }
          @Override public void writeTo(BufferedSink sink) throws IOException {
            sink.writeAll(clone);
          }
        })
        .build();
  }

  static Response bufferBody(Response response, Buffer buffer) throws IOException {
    final ResponseBody body = response.body();
    if (body == null) {
      return response;
    }

    buffer.writeAll(body.source());

    final Buffer clone = buffer.clone();
    return response.newBuilder().body(new ResponseBody() {
      @Override public MediaType contentType() {
        return body.contentType();
      }
      @Override public long contentLength() {
        return body.contentLength();
      }
      @Override public BufferedSource source() {
        return clone.clone();
      }
    }).build();
  }

  static <T> void validateServiceClass(Class<T> service) {
    if (!service.isInterface()) {
      throw new IllegalArgumentException("Only interface endpoint definitions are supported.");
    }
    // Prevent API interfaces from extending other interfaces. This not only avoids a bug in
    // Android (http://b.android.com/58753) but it forces composition of API declarations which is
    // the recommended pattern.
    if (service.getInterfaces().length > 0) {
      throw new IllegalArgumentException("Interface definitions must not extend other interfaces.");
    }
  }

  static class SynchronousExecutor implements Executor {
    @Override public void execute(Runnable runnable) {
      runnable.run();
    }
  }

  private Utils() {
    // No instances.
  }
}
