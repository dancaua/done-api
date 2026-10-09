package org.adancau.doneapi.appliance;

import java.util.List;

public enum ApplianceKind {
  washer("Mașină de spălat", "WasherDone"),
  dryer("Uscător de rufe", "DryerDone"),
  dishwasher("Mașină de spălat vase", "DishDone"),
  oven("Cuptor", "OvenDone"),
  hob("Aragaz / plită", "HobDone"),
  custom("device.custom.title", "CustomDone");
  public final String title, defaultName;

  ApplianceKind(String title, String defaultName) {
    this.title = title;
    this.defaultName = defaultName;
  }

  public boolean needsCollection() {
    return this == washer || this == dryer || this == dishwasher;
  }

  public record Suggestion(String name, int minutes, String localizationKey) {
    public Suggestion(String name,int minutes) { this(name,minutes,name); }
  }

  public List<Suggestion> suggestions() {
    return switch (this) {
      case washer ->
          List.of(
              new Suggestion("Bumbac 40°C", 96),
              new Suggestion("Rapid 30°C", 30),
              new Suggestion("Eco 40–60°C", 210));
      case dryer ->
          List.of(
              new Suggestion("Bumbac", 120),
              new Suggestion("Delicate", 60),
              new Suggestion("Rapid", 30));
      case dishwasher ->
          List.of(
              new Suggestion("Eco 50°C", 180),
              new Suggestion("Intensiv 70°C", 120),
              new Suggestion("Rapid", 45));
      case oven ->
          List.of(
              new Suggestion("Încălzire", 10),
              new Suggestion("Coacere", 30),
              new Suggestion("Rumenire", 15));
      case custom -> List.of();
      case hob ->
          List.of(
              new Suggestion("Timer scurt", 5),
              new Suggestion("Timer mediu", 10),
              new Suggestion("Timer lung", 20));
    };
  }
}
