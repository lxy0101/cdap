/*
 * Copyright © 2014 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.api.service.http;

import co.cask.cdap.api.templates.AdapterPluginRegistry;

import java.util.Map;

/**
 * Interface which should be implemented to configure a {@link HttpServiceHandler}
 */
public interface HttpServiceConfigurer extends AdapterPluginRegistry {

  /**
   * Sets a set of properties that will be available through the {@link HttpServiceHandlerSpecification#getProperties()}
   * at runtime.
   *
   * @param properties the properties to set
   */
  void setProperties(Map<String, String> properties);

  /**
   * Adds the names of {@link co.cask.cdap.api.dataset.Dataset DataSets} used by the handler.
   *
   * @param datasets Dataset names.
   */
  void useDatasets(Iterable<String> datasets);
}
