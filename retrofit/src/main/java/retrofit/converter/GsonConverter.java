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
package retrofit.converter;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.ResponseBody;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import okio.BufferedSink;

/**
 * A {@link Converter} which uses GSON for serialization and deserialization of entities.
 *
 * @author Jake Wharton (jw@squareup.com)
 */
public class GsonConverter implements Converter {
  private final Gson gson;
  private Charset charset;

  /**
   * Create an instance using the supplied {@link Gson} object for conversion. Encoding to JSON and
   * decoding from JSON (when no charset is specified by a header) will use UTF-8.
   */
  public GsonConverter(Gson gson) {
    this(gson, "UTF-8");
  }

  /**
   * Create an instance using the supplied {@link Gson} object for conversion. Encoding to JSON and
   * decoding from JSON (when no charset is specified by a header) will use the specified charset.
   */
  public GsonConverter(Gson gson, String charset) {
    this.gson = gson;
    this.charset = Charset.forName(charset);
  }

  @Override public Object fromBody(ResponseBody body, Type type) throws ConversionException {
    Reader reader = null;
    try {
      reader = body.charStream();
      return gson.fromJson(reader, type);
    } catch (JsonParseException e) {
      throw new ConversionException(e);
    } finally {
      if (reader != null) {
        try {
          reader.close();
        } catch (IOException ignored) {
        }
      }
    }
  }

  @Override public RequestBody toBody(Object object) {
    final byte[] bytes = gson.toJson(object).getBytes(charset);
    final MediaType contentType = MediaType.parse("application/json; charset=" + charset);
    return new RequestBody() {
      @Override public MediaType contentType() {
        return contentType;
      }

      @Override public long contentLength() {
        return bytes.length;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        sink.write(bytes);
      }
    };
  }
}
