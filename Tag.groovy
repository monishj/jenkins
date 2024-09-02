package com.axis.maximus.jenkins.library

class Tag {
    def steps;
    Tag(steps) {
        this.steps = steps
    }
  def tagRepo() {
        steps.sh 'echo "tagging repo from jenkins shared library"'

  }


}
