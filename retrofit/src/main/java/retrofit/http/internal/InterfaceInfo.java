// Copyright 2013 Square, Inc.
package retrofit.http.internal;

import java.util.List;

public class InterfaceInfo {
  final String packageName;
  final String className;
  final CharSequence classFqcn;
  final String interfaceName;
  final List<MethodInfo> methodInfos;

  public InterfaceInfo(String packageName, String className,
      String interfaceName, List<MethodInfo> methodInfos) {
    this.packageName = packageName;
    this.className = className;
    this.classFqcn = packageName + "." + className;
    this.interfaceName = interfaceName;
    this.methodInfos = methodInfos;
  }
}
