/*
 * java-tron is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * java-tron is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.tron.common.application;

import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j(topic = "app")
@Component
public class ServiceContainer {

  @Autowired
  private List<Service> services;

  private List<Service> enabledServices;

  public ServiceContainer() {
  }

  @PostConstruct
  private void initEnabledServices() {
    this.enabledServices = this.services.stream()
        .filter(Service::isEnable)
        .collect(Collectors.toList());
  }

  void start() {
    logger.info("Starting api services.");
    this.enabledServices.forEach(Service::start);
    logger.info("All api services started.");
  }

  void stop() {
    logger.info("Stopping api services.");
    this.enabledServices.forEach(Service::stop);
    logger.info("All api services stopped.");
  }
}
