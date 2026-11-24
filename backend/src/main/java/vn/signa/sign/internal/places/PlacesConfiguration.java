package vn.signa.sign.internal.places;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlacesConfiguration.PlacesProperties.class)
public class PlacesConfiguration {

  @Bean
  PlaceSearch placeSearch(PlacesProperties properties) {
    return switch (properties.provider()) {
      case NONE -> new NoopPlaceSearch();
    };
  }

  @ConfigurationProperties("signa.places")
  record PlacesProperties(@DefaultValue("none") Provider provider) {}

  enum Provider {
    NONE
  }
}
