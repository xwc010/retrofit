// Copyright 2013 Square, Inc.
package retrofit.http.internal;

import java.util.ArrayList;
import java.util.List;

final class MethodInfo {
  final String name;
  final String httpMethod;
  final boolean httpMethodHasBody;
  final String url;
  final List<String> urlParamNames;
  final List<String> methodParamTypes;
  final List<String> methodParamNames;
  final boolean isSynchronous;
  final boolean isMultipart;
  final String returnType;

  private MethodInfo(String name, String httpMethod, boolean httpMethodHasBody, String url,
      List<String> urlParamNames, List<String> methodParamTypes, List<String> methodParamNames,
      boolean isSynchronous, boolean isMultipart, String returnType) {
    this.name = name;
    this.httpMethod = httpMethod;
    this.httpMethodHasBody = httpMethodHasBody;
    this.url = url;
    this.urlParamNames = urlParamNames;
    this.methodParamTypes = methodParamTypes;
    this.methodParamNames = methodParamNames;
    this.isSynchronous = isSynchronous;
    this.isMultipart = isMultipart;
    this.returnType = returnType;
  }

  static class Builder {
    private final String name;
    private String httpMethod;
    private Boolean httpMethodHasBody;
    private String url;
    private final List<String> urlParamNames = new ArrayList<String>();
    private final List<String> methodParamTypes = new ArrayList<String>();
    private final List<String> methodParamNames = new ArrayList<String>();
    private boolean isSynchronous = false;
    private boolean isMultipart = false;
    private String returnType;

    Builder(String name) {
      this.name = name;
    }

    Builder method(String method) {
      // TODO not null
      // TODO not set
      httpMethod = method;
      return this;
    }

    Builder hasBody(boolean hasBody) {
      // TODO not set
      httpMethodHasBody = hasBody;
      return this;
    }

    boolean hasBody() {
      if (httpMethodHasBody == null) {
        throw new IllegalStateException("Has body property not set.");
      }
      return httpMethodHasBody;
    }

    Builder url(String url) {
      // TODO not null
      // TODO not set
      this.url = url;
      return this;
    }

    Builder addUrlParamName(String name) {
      // TODO not null
      urlParamNames.add(name);
      return this;
    }

    Builder addMethodParam(String type, String name) {
      // TODO not nulls
      methodParamTypes.add(type);
      methodParamNames.add(name);
      return this;
    }

    Builder synchronous(boolean synchronous) {
      // TODO not set
      isSynchronous = synchronous;
      return this;
    }

    Builder multipart(boolean multipart) {
      // TODO not set
      isMultipart = multipart;
      return this;
    }

    Builder returnType(String type) {
      // TODO not null
      // TODO not set
      returnType = type;
      return this;
    }

    MethodInfo build() {
      // TODO check things
      return new MethodInfo(name, httpMethod, httpMethodHasBody, url, urlParamNames,
          methodParamTypes, methodParamNames, isSynchronous, isMultipart, returnType);
    }
  }
}
