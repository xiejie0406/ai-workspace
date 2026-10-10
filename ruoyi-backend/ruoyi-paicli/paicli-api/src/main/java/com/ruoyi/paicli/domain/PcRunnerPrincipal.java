package com.ruoyi.paicli.domain;

public record PcRunnerPrincipal(long runnerId,long directoryContextId,long ownerId,long credentialId,long runnerVersion,String kind) { }
