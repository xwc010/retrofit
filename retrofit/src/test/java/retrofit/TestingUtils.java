// Copyright 2013 Square, Inc.
package retrofit;

import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.RequestBody;
import java.io.IOException;
import java.lang.reflect.Method;
import okio.BufferedSink;

public final class TestingUtils {
  public static Method onlyMethod(Class c) {
    Method[] declaredMethods = c.getDeclaredMethods();
    if (declaredMethods.length == 1) {
      return declaredMethods[0];
    }
    throw new IllegalArgumentException("More than one method declared.");
  }

  static RequestBody stringBody(final String body) {
    return new RequestBody() {
      @Override public MediaType contentType() {
        return MediaType.parse("text/plain; charset=UTF-8");
      }

      @Override public long contentLength() {
        return Character.codePointCount(body, 0, body.length());
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        sink.writeUtf8(body);
      }

      @Override public String toString() {
        return "String[" + body + ']';
      }
    };
  }
}
