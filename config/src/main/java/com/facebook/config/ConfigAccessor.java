package com.facebook.config;

import com.facebook.collections.ByteArray;
import com.facebook.collections.Mapper;
import org.apache.log4j.Logger;
import org.joda.time.Duration;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.String;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 1. wraps a JSONObject that contains configuration information.
 * 2. converts JSONExceptions to a runtime ConfigException
 * 3. provides clean conversions to primitive types and annotated bean classes
 */
public class ConfigAccessor {
  private static final Logger LOG = Logger.getLogger(ConfigAccessor.class);

  private final JSONObject jsonObject;

  public ConfigAccessor(JSONObject jsonObject) {
    this.jsonObject = jsonObject;
  }

  public static ConfigAccessor emptyConfig() {
    return new ConfigAccessor(new JSONObject());
  }

  public <T> T getBean(
    String key,
    Class<? extends ExtractableBeanBuilder<T>> beanBuilderClass
  ) {
    try {
      JSONObject jsonBean = get(key, null, new JSONObjectExtractor());
      ConfigAccessor jsonBeanAccessor = new ConfigAccessor(jsonBean);
      Object beanBuilder = beanBuilderClass.newInstance();

      for (Method m : beanBuilderClass.getMethods()) {
        if (m.getName().toLowerCase().startsWith("set")) {
          FieldExtractor extractor = m.getAnnotation(FieldExtractor.class);

          if (extractor != null) {
            if (!Extractor.class.isAssignableFrom(extractor.extractorClass())) {
              String message = String.format("extractor %s does not extend Extractor.class",
                extractor
              );

              LOG.error(message);

              throw new ConfigException(message);
            }

            // if the parameter isn't optional check, or if it's optional
            // and it is present; ie, if it's not optional and not there, let
            // get() throw a ConfigException
            if (!extractor.optional() || jsonBeanAccessor.hasKey(extractor.key())) {
              Object value = jsonBeanAccessor.get(
                extractor.key(),
                null,
                (Extractor) extractor.extractorClass().newInstance()
              );

              m.invoke(beanBuilder, value);
            }
          } else {
            LOG.warn("unable to find annotation for setter method " + m.getName());
          }
        }
      }

      return ((ExtractableBeanBuilder<T>) beanBuilder).build();
    } catch (InstantiationException e) {
      throw new ConfigException(e);
    } catch (IllegalAccessException e) {
      throw new ConfigException(e);
    } catch (InvocationTargetException e) {
      throw new ConfigException(e);
    }
  }

  public Class<?> getClass(String key, Class<?> defaultValue) {
    return get(key, defaultValue, new ClassExtractor());
  }

  public boolean hasKey(String key) {
    return jsonObject.has(key);
  }

  public Duration getDuration(String key, String defaultValue) {
    return new Duration(getDurationMillis(key, defaultValue));
  }

  public long getDurationSeconds(String key, String defaultValue) {
    return ConfigUtil.getDurationMillis(getString(key, defaultValue)) / 1000;
  }

  public long getDurationMillis(String key, String defaultValue) {
    return ConfigUtil.getDurationMillis(getString(key, defaultValue));
  }

  public long getSizeBytes(String key, String defaultValue) {
    return ConfigUtil.getSizeBytes(getString(key, defaultValue));
  }

  public long getSizeBytes(String key, Long defaultValue) {
    return hasKey(key) ?
      ConfigUtil.getSizeBytes(getString(key)) : defaultValue;
  }

  public Boolean getBoolean(String key) throws ConfigException {
    return getBoolean(key, null);
  }

  public Boolean getBoolean(String key, Boolean defaultValue)
    throws ConfigException {
    return get(key, defaultValue, new BooleanExtractor());
  }

  public String getString(String key) throws ConfigException {
    return getString(key, null);
  }

  public String getString(String key, String defaultValue) {
    return get(key, defaultValue, new StringExtractor());
  }

  public int getInt(String key) throws ConfigException {
    return getInt(key, null);
  }

  public int getInt(String key, Integer defaultValue) {
    return get(key, defaultValue, new IntegerExtractor());
  }

  public long getLong(String key) throws ConfigException {
    return getLong(key, null);
  }

  public long getLong(String key, Long defaultValue) {
    return get(key, defaultValue, new LongExtractor());
  }

  public double getDouble(String key) {
    return getDouble(key, null);
  }

  public double getDouble(String key, Double defaultValue) {
    return get(key, defaultValue, new DoubleExtractor());
  }

  public Map<String, String> getStringMap(String key) {
    return get(key, null, new Extractor<Map<String, String>>() {
      @Override
      public Map<String, String> extract(String key, JSONObject jsonObject)
        throws JSONException {
        Map<String, String> map = new HashMap<String, String>();
        JSONObject jsonMap = jsonObject.getJSONObject(key);
        ConfigAccessor mapAccessor = new ConfigAccessor(jsonMap);
        Iterator<String> keys = jsonMap.keys();

        while (keys.hasNext()) {
          String mapKey = keys.next();

          map.put(mapKey, mapAccessor.getString(mapKey));
        }

        return map;
      }
    });
  }

  public <T> List<T> getList(String key, Mapper<String, T> converter) {
    List<T> result = new ArrayList<T>();

    for (String item : getStringList(key)) {
      result.add(converter.map(item));
    }

    return result;
  }

  public List<String> getStringList(String key) throws ConfigException {
    JSONArray items;
    List<String> result = new ArrayList<String>();

    try {
      items = jsonObject.getJSONArray(key);

      for (int i = 0; i < items.length(); i++) {
        result.add(items.getString(i));
      }
    } catch (JSONException e) {
      throw new ConfigException(
        "unable to parse string list for key " + key,
        e
      );
    }

    return result;
  }

  public List<ByteArray> getByteArrayList(String key) throws ConfigException {
    List<ByteArray> result = new ArrayList<ByteArray>();
    for (String item : getStringList(key)) {
      result.add(new ByteArray(item.getBytes()));
    }
    return result;
  }

  public List<String> getKeys() {
    List<String> keys = new ArrayList<String>();
    Iterator<String> keysIterator = jsonObject.keys();
    while (keysIterator.hasNext()) {
      keys.add(keysIterator.next());
    }
    return keys;
  }

  private <T> T get(String key, T defaultValue, Extractor<T> extractor)
    throws ConfigException {
    try {
      if (jsonObject.has(key)) {
        return extractor.extract(key, jsonObject);
      } else if (defaultValue != null) {
        return defaultValue;
      } else {
        throw new ConfigException("missing property: " + key);
      }
    } catch (JSONException e) {
      throw new ConfigException(e);
    }
  }

  @Override
  public String toString() {
    return jsonObject.toString();
  }

  public String toString(int indentFactor)
    throws ConfigException {
    try {
      return jsonObject.toString(indentFactor);
    } catch (JSONException e) {
      throw new ConfigException(e);
    }
  }
}
