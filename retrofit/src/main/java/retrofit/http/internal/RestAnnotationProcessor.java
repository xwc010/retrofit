// Copyright 2013 Square, Inc.
package retrofit.http.internal;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;
import retrofit.http.DELETE;
import retrofit.http.GET;
import retrofit.http.HEAD;
import retrofit.http.Multipart;
import retrofit.http.POST;
import retrofit.http.PUT;

import static javax.lang.model.element.ElementKind.INTERFACE;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.tools.Diagnostic.Kind.ERROR;

/**
 * TODO, bitches!
 *
 * - if HEAD don't allow non-Response type
 * - Request body non-multipart should be last argument
 */
@SupportedAnnotationTypes({ //
    "retrofit.http.DELETE", //
    "retrofit.http.GET", //
    "retrofit.http.HEAD", //
    "retrofit.http.POST", //
    "retrofit.http.PUT" //
})
public class RestAnnotationProcessor extends AbstractProcessor {
  public static final String SUFFIX = "$$RetrofitImpl";

  @Override public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  private void error(Element element, String message, Object... args) {
    processingEnv.getMessager().printMessage(ERROR, String.format(message, args), element);
  }

  @Override public boolean process(Set<? extends TypeElement> elements, RoundEnvironment env) {
    Map<TypeElement, InterfaceInfo> infos = new LinkedHashMap<TypeElement, InterfaceInfo>();

    // Find, parse, and validate all methods and their containing interfaces.
    for (TypeElement interfaceType : findInterfaceTypes(env)) {
      infos.put(interfaceType, parseInterfaceInfo(interfaceType));
    }

    // Write out each interface's implementation.
    for (Map.Entry<TypeElement, InterfaceInfo> entry : infos.entrySet()) {
      writeImplementation(entry.getKey(), entry.getValue());
    }

    return true;
  }

  private InterfaceInfo parseInterfaceInfo(TypeElement interfaceType) {
    List<MethodInfo> methodInfos = new ArrayList<MethodInfo>();
    for (Element enclosedElement : interfaceType.getEnclosedElements()) {
      if (!(enclosedElement instanceof ExecutableElement)) {
        continue; // Non-method element.
      }
      ExecutableElement executableElement = (ExecutableElement) enclosedElement;
      MethodInfo methodInfo = parseMethodInfo(executableElement);
      if (methodInfo != null) {
        methodInfos.add(methodInfo);
      }
    }

    String packageName =
        processingEnv.getElementUtils().getPackageOf(interfaceType).getQualifiedName().toString();
    String interfaceFqcn = interfaceType.getQualifiedName().toString();
    String interfaceName = interfaceFqcn.substring(packageName.length() + 1);
    String className = interfaceName.replace('.', '$') + SUFFIX;

    return new InterfaceInfo(packageName, className, interfaceName, methodInfos);
  }

  private MethodInfo parseMethodInfo(ExecutableElement element) {
    Types typeUtils = processingEnv.getTypeUtils();
    Elements elementUtils = processingEnv.getElementUtils();

    TypeElement callbackElement = elementUtils.getTypeElement("retrofit.http.Callback");
    TypeMirror callbackType = typeUtils.erasure(callbackElement.asType());

    String name = element.getSimpleName().toString();
    List<? extends VariableElement> parameters = element.getParameters();

    MethodInfo.Builder builder = new MethodInfo.Builder(name);

    // Look for a return type to see if the method is synchronous.
    TypeMirror returnType = element.getReturnType();
    boolean synchronous = !returnType.getKind().equals(TypeKind.VOID);
    builder.synchronous(synchronous);
    int paramCount = parameters.size();
    if (synchronous) {
      builder.returnType(returnType.toString());
    } else {
      if (parameters.isEmpty()) {
        error(element, "need callback");
        return null;
      }
      VariableElement callbackParam = parameters.get(paramCount - 1);
      paramCount -= 1;
      // TODO verify callback element
      // TODO get return type
    }

    // Get the URL, HTTP method, and whether or not the request has a body.
    if (!parseHttpMethodAnnotation(builder, element)) {
      return null;
    }

    // Check for multipart declaration.
    Multipart multipart = element.getAnnotation(Multipart.class);
    boolean isMultipart = multipart != null;
    if (isMultipart) {
      if (!builder.hasBody()) {
        error(element, "Multipart request requires HTTP methods with a body (e.g, POST).");
        return null;
      }
      // TODO
    }
    builder.multipart(isMultipart);

    for (int i = 0; i < paramCount; i++) {
      VariableElement parameter = parameters.get(i);
      TypeMirror parameterType = parameter.asType();
      String parameterTypeName = parameterType.toString();
      String parameterName = parameter.getSimpleName().toString();
      builder.addMethodParam(parameterTypeName, parameterName);
    }

    return builder.build();
  }

  /*
   * This method is fairly dumb in logic. We should improve it to be more modular and dynamic
   * moving forward.
   */
  boolean parseHttpMethodAnnotation(MethodInfo.Builder builder, ExecutableElement element) {
    int found = 0;

    DELETE delete = element.getAnnotation(DELETE.class);
    if (delete != null) {
      builder.method("DELETE").hasBody(false).url(delete.value());
      found += 1;
    }
    GET get = element.getAnnotation(GET.class);
    if (get != null) {
      builder.method("GET").hasBody(false).url(get.value());
      found += 1;
    }
    HEAD head = element.getAnnotation(HEAD.class);
    if (head != null) {
      builder.method("HEAD").hasBody(false).url(head.value());
      found += 1;
    }
    POST post = element.getAnnotation(POST.class);
    if (post != null) {
      builder.method("POST").hasBody(true).url(post.value());
      found += 1;
    }
    PUT put = element.getAnnotation(PUT.class);
    if (put != null) {
      builder.method("PUT").hasBody(true).url(put.value());
      found += 1;
    }

    if (found == 0) {
      error(element, "No method/urls");
      return false;
    }
    if (found > 1) {
      error(element, "Multiple method/urls");
      return false;
    }
    return true;
  }

  private Set<TypeElement> findInterfaceTypes(RoundEnvironment env) {
    Set<TypeElement> interfaceTypes = new LinkedHashSet<TypeElement>();

    for (Element restMethod : getRestMethods(env)) {
      TypeElement interfaceType = (TypeElement) restMethod.getEnclosingElement();
      if (interfaceType.getKind() != INTERFACE) {
        error(interfaceType, "Retrofit HTTP methods can only be declared on interfaces.");
        continue;
      }
      if (interfaceType.getModifiers().contains(PRIVATE)) {
        error(interfaceType, "Retrofit interfaces must not be private.");
        continue;
      }
      interfaceTypes.add(interfaceType);
    }

    return interfaceTypes;
  }

  private Set<? extends Element> getRestMethods(RoundEnvironment env) {
    Set<Element> methods = new LinkedHashSet<Element>();
    methods.addAll(env.getElementsAnnotatedWith(DELETE.class));
    methods.addAll(env.getElementsAnnotatedWith(GET.class));
    methods.addAll(env.getElementsAnnotatedWith(HEAD.class));
    methods.addAll(env.getElementsAnnotatedWith(POST.class));
    methods.addAll(env.getElementsAnnotatedWith(PUT.class));
    return methods;
  }

  private void writeImplementation(TypeElement interfaceType, InterfaceInfo info) {
    Filer filer = processingEnv.getFiler();
    Writer writer = null;
    try {
      JavaFileObject source = filer.createSourceFile(info.classFqcn, interfaceType);
      writer = source.openWriter();
      writeInterfaceImplementation(writer, info);
    } catch (IOException e) {
      error(interfaceType, e.getMessage());
    } finally {
      if (writer != null) {
        try {
          writer.close();
        } catch (IOException ignored) {
        }
      }
    }
  }

  static void writeInterfaceImplementation(Writer writer, InterfaceInfo info) throws IOException {
    writer.write("// Generated by Retrofit. Do not modify!\n");
    writer.write("package ");
    writer.write(info.packageName);
    writer.write(";\n\n");

    writer.write("import java.lang.reflect.Type;\n");
    writer.write("import java.util.List;\n");
    writer.write("import retrofit.http.Header;\n");
    writer.write("import retrofit.http.RestAdapter;\n");
    writer.write("import retrofit.http.client.Request;\n");
    writer.write("import retrofit.http.client.Response;\n");
    writer.write("import retrofit.http.mime.TypedInput;\n");
    writer.write("import retrofit.http.mime.TypedOutput;\n");

    writer.write("\n" + "public class ");
    writer.write(info.className);
    writer.write(" extends RestAdapter.RestHandler implements ");
    writer.write(info.interfaceName);
    writer.write(" {\n");

    // Create constructor which calls super with the supplied RestAdapter instance.
    writer.write("  public ");
    writer.write(info.className);
    writer.write("(RestAdapter restAdapter) {\n");
    writer.write("    super(restAdapter);\n");
    writer.write("  }\n");

    for (MethodInfo methodInfo : info.methodInfos) {
      writeMethodImplementation(writer, methodInfo);
    }
    writer.write("}\n");
    writer.flush();
    writer.close();
  }

  private static void writeMethodImplementation(Writer writer, MethodInfo methodInfo)
      throws IOException {
    boolean isSynchronous = methodInfo.isSynchronous;
    int argumentCount = methodInfo.methodParamNames.size();
    if (!isSynchronous) {
      argumentCount -= 1; // Callback will be the last argument.
    }

    String returnType = "void";
    if (isSynchronous) {
      returnType = methodInfo.returnType;
    }

    writer.write("\n  @Override public ");
    writer.write(returnType);
    writer.write("\n  ");
    writer.write(methodInfo.name);
    writer.write("(\n");

    for (int i = 0, count = methodInfo.methodParamNames.size(); i < count; i++) {
      writer.write("    ");
      String paramName = methodInfo.methodParamNames.get(i);
      String paramType = methodInfo.methodParamTypes.get(i);

      writer.write(paramType);
      writer.write(" ");

      if (i == count - 1 && !isSynchronous) {
        writer.write("callback");
      } else {
        writer.write("arg");
        writer.write(Integer.toString(i));
        if (i < count - 1) {
          writer.write(",");
        } else {
          writer.write(" ");
        }
        writer.write(" // ");
        writer.write(paramName);
      }
      writer.write("\n");
    }

    writer.write("  ) {\n");

    // Create the URL builder and add the base host.
    writer.write("    // Create the URL builder and add the base host\n");
    writer.write("    String host = server.getUrl();\n");
    writer.write("    int lastCharIndex = host.length() - 1;\n");
    writer.write("    StringBuilder urlBuilder = new StringBuilder();\n");
    writer.write("    urlBuilder.append(host);\n");
    writer.write("    if (host.charAt(lastCharIndex) == '/') {\n");
    writer.write("      urlBuilder.deleteCharAt(lastCharIndex); // Guard against double slash.\n");
    writer.write("    }\n");

    // Assemble URL using path replacement and query params, if present.
    writer.write("\n    // Assemble URL using path replacement and query params, if present.\n");
    String url = methodInfo.url;
    BitSet usedArgNames = new BitSet(argumentCount);
    int end = -1;
    for (int start = url.indexOf('{'); start != -1; start = url.indexOf('{', end)) {
      if (end + 1 < start) {
        writer.write("    urlBuilder.append(\"");
        writer.write(url.substring(end + 1, start));
        writer.write("\");\n");
      }

      end = url.indexOf('}', start);
      if (end == -1) {
        break; // No more keys!
      }
      String key = url.substring(start + 1, end);
      boolean found = false;
      for (int i = 0; i < argumentCount; i++) {
        if (methodInfo.methodParamNames.get(i).equals(key)) {
          writer.write("    urlBuilder.append(urlEncode(arg");
          writer.write(Integer.toString(i));
          writer.write(")); // ");
          writer.write(key);
          writer.write("\n");
          found = true;
          usedArgNames.set(i);
          break;
        }
      }
      if (!found) {
        throw new IllegalStateException("Unable to find argument for URL key: " + key);
      }
    }
    if (end + 1 < url.length()) {
      writer.write("    urlBuilder.append(\"");
      writer.write(url.substring(end + 1));
      writer.write("\");\n");
    }
    if (!methodInfo.httpMethodHasBody) {
      // Remaining arguments are appended as query parameters on the URL.
      boolean first = true;
      for (int i = 0; i < argumentCount; i++) {
        if (usedArgNames.get(i)) {
          continue;
        }
        String paramName = methodInfo.methodParamNames.get(i);
        writer.write("    urlBuilder.append(\"");
        writer.write(first ? "?" : "&");
        writer.write(paramName);
        writer.write("=\");\n");
        writer.write("    urlBuilder.append(urlEncode(arg");
        writer.write(Integer.toString(i));
        writer.write(")); // ");
        writer.write(paramName);
        writer.write("\n");
        first = false;
      }
    }
    writer.write("    String url = urlBuilder.toString();\n");

    if (!methodInfo.httpMethodHasBody) {
      writer.write("    TypedOutput body = null;\n");
    } else if (methodInfo.isMultipart) {
      // TODO multipart body
      writer.write("    TypedOutput body = null;\n");
    } else {
      int bodyIndex = argumentCount - 1;
      writer.write("    TypedOutput body = converter.toBody(arg");
      writer.write(Integer.toString(bodyIndex));
      writer.write("); // ");
      writer.write(methodInfo.methodParamNames.get(bodyIndex));
      writer.write("\n");
    }

    // Assemble the request object.
    writer.write("\n    // Assemble the request object.\n");
    writer.write("    Request request = new Request(\"");
    writer.write(methodInfo.httpMethod);
    writer.write("\", url, headers.get(), body);\n");

    // Execute the request.
    writer.write("\n    // Execute the request.\n");
    if (isSynchronous) {
      writer.write("    ResponseWrapper response = execute(request);\n");
    } else {
      writer.write("    asyncExecute(request, callback);\n");
    }

    writer.write("  }\n");
  }
}
