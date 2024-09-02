package com.axis.maximus.jenkins.library

import com.cloudbees.hudson.plugins.folder.*
import com.cloudbees.hudson.plugins.folder.properties.*
import com.cloudbees.hudson.plugins.folder.properties.FolderCredentialsProvider.FolderCredentialsProperty
import com.cloudbees.plugins.credentials.*
import com.cloudbees.plugins.credentials.common.*
import com.cloudbees.plugins.credentials.domains.*
import com.cloudbees.plugins.credentials.impl.*
import org.jenkinsci.plugins.plaincredentials.*
import org.jenkinsci.plugins.plaincredentials.impl.*
import hudson.FilePath
import hudson.util.Secret

class Credentials implements Serializable {

    def credential
    def environment
    def steps

    Credentials(environment,steps) {
        this.environment = environment
        this.steps = steps
    }
    def searchCredential(Map config = [:]) {
        def domain = com.cloudbees.plugins.credentials.domains.Domain.global()
        def abstract List<com.cloudbees.plugins.credentials.Credentials> credentialsList

        if(environment == 'Jenkins Global Store'){
            credentialsList = SystemCredentialsProvider.getInstance().getStore().getCredentials(domain)
        }
        else {
            for (folder in Jenkins.getInstance().getAllItems(Folder.class)) {
                if(folder.name.equals(environment)){
                    AbstractFolder<?> folderAbs = AbstractFolder.class.cast(folder)
                    FolderCredentialsProperty property = folderAbs.getProperties().get(FolderCredentialsProperty.class)
                    if(property) {
                        credentialsList = property.getStore().getCredentials(domain)
                    }
                }
            }
        }
        for (cred in credentialsList) {
            if(cred.getId() == config.ID)
                return true
        }
    }

    def addCredential(Map config = [:]) {
        def domain = com.cloudbees.plugins.credentials.domains.Domain.global()
        def store
        def abstract List<com.cloudbees.plugins.credentials.Credentials> credentialsList   
        
        if(environment == 'Jenkins Global Store'){
            store = SystemCredentialsProvider.getInstance().getStore()
        }
        else {
            for (folder in Jenkins.getInstance().getAllItems(Folder.class)) {
                if(folder.name.equals(environment)){
                    AbstractFolder<?> folderAbs = AbstractFolder.class.cast(folder)
                    FolderCredentialsProperty property = folderAbs.getProperties().get(FolderCredentialsProperty.class)
                    if(property) {
                        store = property.getStore()                       
                    }
                    else {
                        property = new FolderCredentialsProperty([credential])
                        folderAbs.addProperty(property)
                    }
                }
            }
        }

        credentialsList = store.getCredentials(domain)
      for (cred in credentialsList) {
        if(cred.getId() == credential.getId()){
          store.removeCredentials(domain, cred)
        }
      }
        store.addCredentials(domain, credential)
    }

    def addUsernameAndPassword(Map config = [:]) {
        credential =  new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL,config.ID, config.description,config.username,config.password)    
        addCredential()
    }

    def addSecretText(Map config = [:]) {
        credential = new StringCredentialsImpl(CredentialsScope.GLOBAL,config.ID, config.description,Secret.fromString(config.text))
        addCredential()
    }

    def addSecretFile(Map config = [:]) {
        def fileLocation = "/home/jenkins/agent/workspace/${config.jobname}/${config.ID}"
        def file = new FilePath(Jenkins.getInstance().getComputer(config.nodename).getChannel() ,fileLocation)
        credential = new FileCredentialsImpl(CredentialsScope.GLOBAL,config.ID, config.description,file.getName(), com.cloudbees.plugins.credentials.SecretBytes.fromString(file.readToString()))
        addCredential()
    }

    def isBase64Encoded(String str) {
        def base64Chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/='

        def str_without_special_chars = str.replaceAll("[^$base64Chars]", "")

        try {
            def decodedBytes = str_without_special_chars.decodeBase64()
            println(new String(decodedBytes))
            def encodedStr = decodedBytes.encodeBase64().toString().trim()
            println(encodedStr)
            return str == encodedStr
        } catch (Exception e) {
            return false
        }
    }
}
