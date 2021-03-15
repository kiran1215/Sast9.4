application_environments{
  dev{
    short_name = "dev"
    long_name = "Develop"
  }
  prod{
    short_name = "prod"
    long_name = "prod"
  }
}

keywords{
  master = /^[Mm]aster$/
  develop = /^[Dd]evelop$/
}

libraries{
  github_enterprise
  sonarqube
  docker{
    registry = "docker-registry.default.svc:5000"
    cred = "openshift-docker-registry"
    repo_path_prefix = "my-app-images"
  }
  sdp{
    images{
      registry = "https://docker-registry.default.svc:5000"
      repo = "sdp"
      cred = "openshift-docker-registry"
    }
  }
  openshift{
    // More on these settings in the next section
    url = "https://my-openshift-cluster.ocp.example.com:8443"
    helm_configuration_repository = "https://github.com/kottoson-bah/sdp-example-helm-config.git"
    helm_configuration_repository_credential = github
    tiller_namespace = my-app-tiller
    tiller_credential = my-app-tiller-credential
  }
}
