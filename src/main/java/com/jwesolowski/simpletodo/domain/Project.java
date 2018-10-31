package com.jwesolowski.simpletodo.domain;

import java.util.List;

public class Project implements GenericEntity<Project> {

  private long id;
  private List<Task> tasks;

  @Override
  public long getId() {
    return id;
  }
}