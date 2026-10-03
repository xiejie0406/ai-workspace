package com.ruoyi.paicli.domain;

public record PcRunnerPrincipal(long runnerId,long workspaceId,long ownerId,long credentialId,long runnerVersion,String kind) { }
