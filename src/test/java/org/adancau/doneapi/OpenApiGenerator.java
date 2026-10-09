package org.adancau.doneapi;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** Explicit documentation tool; normal tests never rewrite the contract. */
public final class OpenApiGenerator {
  private static final Map<String,Object> schemas = new TreeMap<>();
  private static final Set<String> requests = Set.of("Register","Login","Refresh","AppleLogin","ForgotPassword","ResetPassword","UpdateProfile",
      "ChangePassword","DeleteAccount","CreateAppliance","MoveAppliance","ApplianceNotificationSettings",
      "RenameAppliance","ProgramInput","UpdateProgram","StartSession","ExtendSession","MeasuredProgram",
      "CreateHousehold","RenameHousehold","CreateProjection","UpdateProjection","SharedSnapshot","ProgramSnapshot");

  private static Map<String,Object> type(Type type) {
    if (type instanceof ParameterizedType p) {
      if (p.getRawType() == List.class) return Map.of("type","array","items",type(p.getActualTypeArguments()[0]));
      if (p.getRawType() == Map.class) return Map.of("type","object","additionalProperties",true);
      if (p.getRawType() == org.springframework.http.ResponseEntity.class) return type(p.getActualTypeArguments()[0]);
      if (p.getRawType() == org.adancau.doneapi.common.PageResponse.class) {
        Type item=p.getActualTypeArguments()[0];
        String name="PageResponse_"+(item instanceof Class<?> c ? c.getSimpleName() : "Object");
        if(!schemas.containsKey(name)) {
          var properties=new LinkedHashMap<String,Object>();
          for(var component:org.adancau.doneapi.common.PageResponse.class.getRecordComponents()) {
            properties.put(component.getName(),component.getGenericType() instanceof ParameterizedType
                ? Map.of("type","array","items",type(item)) : type(component.getGenericType()));
          }
          schemas.put(name,Map.of("type","object","properties",properties,"required",new ArrayList<>(properties.keySet())));
        }
        return Map.of("$ref","#/components/schemas/"+name);
      }
      return type(p.getRawType());
    }
    if (!(type instanceof Class<?> c)) return Map.of("type","object");
    if (c == void.class || c == Void.class) return Map.of();
    if (c == String.class) return Map.of("type","string");
    if (c == UUID.class) return Map.of("type","string","format","uuid");
    if (c == java.time.Instant.class) return Map.of("type","string","format","date-time");
    if (c == java.time.LocalDate.class) return Map.of("type","string","format","date");
    if (c == boolean.class || c == Boolean.class) return Map.of("type","boolean");
    if (c == int.class || c == Integer.class || c == long.class || c == Long.class)
      return Map.of("type","integer","format",c == long.class || c == Long.class ? "int64" : "int32");
    if (c == double.class || c == Double.class) return Map.of("type","number","format","double");
    if (c.isEnum()) return Map.of("type","string","enum",Arrays.stream(c.getEnumConstants()).map(Object::toString).toList());
    if (c.isRecord()) { record(c); return Map.of("$ref","#/components/schemas/"+c.getSimpleName()); }
    return Map.of("type","object");
  }

  private static void record(Class<?> c) {
    if (schemas.containsKey(c.getSimpleName())) return;
    var schema=new LinkedHashMap<String,Object>();schemas.put(c.getSimpleName(),schema);
    var properties=new LinkedHashMap<String,Object>();var required=new ArrayList<String>();
    for (var component:c.getRecordComponents()) {
      Map<String,Object> property=new LinkedHashMap<>(type(component.getGenericType()));
      boolean input=requests.contains(c.getSimpleName());
      // Constraints are also propagated to accessors by record/Bean Validation compilation.
      Annotation[] annotations=component.getAccessor().getAnnotations();
      boolean mandatory=!input || component.getType().isPrimitive();
      if(c.getSimpleName().equals("ProgramSnapshot") && component.getName().equals("name")) mandatory=true;
      for (var annotation:annotations) {
        String name=annotation.annotationType().getSimpleName();
        try {
          if (name.equals("NotNull") || name.equals("NotBlank")) mandatory=true;
          if (name.equals("Min")) property.put("minimum",annotation.annotationType().getMethod("value").invoke(annotation));
          if (name.equals("Max")) property.put("maximum",annotation.annotationType().getMethod("value").invoke(annotation));
          if (name.equals("Pattern")) property.put("pattern",annotation.annotationType().getMethod("regexp").invoke(annotation));
          if (name.equals("Size")) {
            int min=(int)annotation.annotationType().getMethod("min").invoke(annotation), max=(int)annotation.annotationType().getMethod("max").invoke(annotation);
            if (min>0) property.put("minLength",min); if (max<Integer.MAX_VALUE) property.put("maxLength",max);
          }
          if (name.equals("PositiveOrZero")) property.put("minimum",0);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
      }
      if (!component.getType().isPrimitive() && !mandatory)
        property=new LinkedHashMap<>(Map.of("anyOf",List.of(property,Map.of("type","null"))));
      if (!input && !component.getType().isPrimitive()) {
        // Response fields are present; optional references/times may explicitly be null.
        property=new LinkedHashMap<>(Map.of("anyOf",List.of(property,Map.of("type","null"))));
      }
      properties.put(component.getName(),property);if(mandatory)required.add(component.getName());
    }
    schema.put("type","object");schema.put("properties",properties);schema.put("additionalProperties",false);
    if(c.getSimpleName().equals("StartSession")) schema.put("description","mode also accepts the JSON alias timingMode. Preset programId may be paired with a minutes override. All appliance kinds support stopwatch; stopwatch forbids preset ID and minutes override.");
    if(c.getSimpleName().equals("ProgramSnapshot")) schema.put("description","Immutable value snapshot. name is literal user content or a built-in label; localizationKey is optional. Measured values are separate from the original snapshot.");
    if(!required.isEmpty())schema.put("required",required);
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] arguments) throws Exception {
    var json=JsonMapper.builder().build();Path file=Path.of("docs/openapi.json");
    Map<String,Object> doc=json.readValue(Files.readString(file),Map.class);
    var paths=new TreeMap<String,Object>();
    Path source=Path.of("src/main/java");
    try(var files=Files.walk(source)) {
      for(var f:files.filter(p -> p.toString().endsWith("Controller.java")).sorted().toList()) {
        String name=source.relativize(f).toString().replace(java.io.File.separatorChar,'.').replace(".java","");
        Class<?> controller=Class.forName(name);var prefix=controller.getAnnotation(RequestMapping.class);
        String base=prefix==null || prefix.value().length==0 ? "" : prefix.value()[0];
        for(var method:controller.getDeclaredMethods()) {
          for(var annotation:method.getAnnotations()) {
            String verb=switch(annotation.annotationType().getSimpleName()) {
              case "GetMapping" -> "get";case "PostMapping" -> "post";case "PutMapping" -> "put";
              case "PatchMapping" -> "patch";case "DeleteMapping" -> "delete";default -> null; };
            if(verb==null)continue;
            String[] routes=(String[])annotation.annotationType().getMethod("value").invoke(annotation);
            if(routes.length==0) routes=(String[])annotation.annotationType().getMethod("path").invoke(annotation);
            if(routes.length==0) routes=new String[]{""};
            for(String route:routes) {
              String path=base+route;
              var operation=new LinkedHashMap<String,Object>();
              operation.put("operationId",controller.getSimpleName()+"_"+method.getName()+"_"+Integer.toUnsignedString(path.hashCode()));
              operation.put("tags",List.of(controller.getSimpleName().replace("Controller","")));
              boolean publicRoute=!path.startsWith("/api/") || path.startsWith("/api/shares") || path.equals("/api/v1/public-config") || path.startsWith("/api/v1/localizations/") || path.startsWith("/api/v1/auth/") && !path.contains("logout");
              operation.put("security",publicRoute ? List.of() : List.of(Map.of("bearerAuth",List.of())));
              boolean capability=path.startsWith("/api/shares/") && (verb.equals("put") || verb.equals("delete"));
              if(capability) operation.put("security",List.of(Map.of("writerCapability",List.of())));
              var parameters=new ArrayList<Map<String,Object>>();
              for(var parameter:method.getParameters()) {
                var pv=parameter.getAnnotation(PathVariable.class);var rh=parameter.getAnnotation(RequestHeader.class);var rp=parameter.getAnnotation(RequestParam.class);var rb=parameter.getAnnotation(RequestBody.class);
                if(rb!=null)operation.put("requestBody",Map.of("required",rb.required(),"content",Map.of("application/json",Map.of("schema",type(parameter.getParameterizedType())))));
                if(pv!=null||rh!=null||rp!=null) {
                  String parameterName=pv!=null?pv.value():rh!=null?rh.value():rp.value();
                  if(parameterName.isEmpty())parameterName=parameter.getName();
                  if(parameterName.equals("Authorization")) continue; // Represented by the capability security scheme.
                  boolean mandatory=pv!=null || rh!=null && rh.required() || rp!=null && rp.required() && rp.defaultValue().equals(ValueConstants.DEFAULT_NONE);
                  var property=new LinkedHashMap<>(type(parameter.getParameterizedType()));
                  if(rp!=null&&!rp.defaultValue().equals(ValueConstants.DEFAULT_NONE)) {
                    Object value=rp.defaultValue();
                    if(parameter.getType()==int.class)value=Integer.valueOf(rp.defaultValue());
                    if(parameter.getType()==boolean.class)value=Boolean.valueOf(rp.defaultValue());
                    property.put("default",value);
                  }
                  parameters.add(Map.of("name",parameterName,"in",pv!=null?"path":rh!=null?"header":"query","required",mandatory,"schema",property));
                }
              }
              if(!parameters.isEmpty())operation.put("parameters",parameters);
              int status=method.getAnnotation(ResponseStatus.class)==null?200:method.getAnnotation(ResponseStatus.class).value().value();
              var response=new LinkedHashMap<String,Object>();response.put("description","Success");
              if(status!=204) {
                boolean html=!path.startsWith("/api/");
                response.put("content",Map.of(html?"text/html":"application/json",Map.of("schema",html?Map.of("type","string"):type(method.getGenericReturnType()))));
              }
              operation.put("responses",Map.of(String.valueOf(status),response,"default",Map.of("description","Error; code is stable and independent of language.","content",Map.of("application/problem+json",Map.of("schema",Map.of("$ref","#/components/schemas/Problem"))))));
              ((Map<String,Object>)paths.computeIfAbsent(path,k -> new TreeMap<String,Object>())).put(verb,operation);
            }
          }
        }
      }
    }
    for(String group:List.of("auth.AuthDtos","account.AccountDtos","appliance.ApplianceDtos","appliance.ApplianceKind","session.SessionDtos","activity.ActivityDtos","sync.StateDtos","sync.StatisticsService","household.HouseholdDtos","sharing.SharingDtos"))
      for(var nested:Class.forName("org.adancau.doneapi."+group).getDeclaredClasses())if(nested.isRecord())record(nested);
    schemas.put("Problem",Map.of("type","object","properties",Map.of("status",Map.of("type","integer"),"code",Map.of("type","string"),"detail",Map.of("type","string")),"required",List.of("status","code","detail")));
    doc.put("paths",paths);doc.put("components",Map.of("schemas",schemas,"securitySchemes",Map.of("bearerAuth",Map.of("type","http","scheme","bearer","bearerFormat","JWT"),"writerCapability",Map.of("type","http","scheme","bearer","bearerFormat","Private write capability","description","43-character base64url private writer key; never a JWT and never included in the public link."))));
    doc.put("servers",List.of(Map.of("url","http://127.0.0.1:8080","description","Local development; configure PUBLIC_ORIGIN for deployed links.")));
    Files.writeString(file,json.writerWithDefaultPrettyPrinter().writeValueAsString(doc)+"\n");
    System.out.println("OpenAPI: "+paths.size()+" routes, "+schemas.size()+" schemas.");
  }
}
