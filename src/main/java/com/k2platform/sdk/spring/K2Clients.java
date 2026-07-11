package com.k2platform.sdk.spring;

import com.k2platform.sdk.K2Client;
import com.k2platform.sdk.K2ConfigFileSource;
import com.k2platform.sdk.OfflineConfigCache;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Paths;
import java.time.Duration;

/** Builds a {@link K2Client} from bound {@link K2Properties} — shared by Mode 1 and Mode 2. */
final class K2Clients {

    private K2Clients() {}

    static K2Client from(K2Properties props) {
        K2Client.Builder builder = K2Client.builder()
                .baseUrl(props.getBaseUrl())
                .token(props.resolveToken())
                .tokenEnc(props.getTokenEnc())
                .defaultEnvironment(props.getEnv())
                .organization(props.getOrg())
                .application(props.getApp())
                .source(K2Client.Source.valueOf(props.getSource().name()))
                .fileSource(new K2ConfigFileSource(new ObjectMapper(),
                        props.getConfigDir(), props.getConfigFile()))
                .cacheTtl(Duration.ofSeconds(props.getCache().getTtlSeconds()));

        if (props.getSts().isEnabled()) {
            builder.stsSigner(new com.k2platform.sdk.AwsStsIdentitySigner());
        }

        if (props.getOfflineCache().isEnabled()) {
            String dir = props.getOfflineCache().getDir();
            long ttlMillis = props.getOfflineCache().getTtlSeconds() * 1000L;
            builder.offlineCache(dir == null || dir.isBlank()
                    ? new OfflineConfigCache(new com.fasterxml.jackson.databind.ObjectMapper(),
                            OfflineConfigCache.defaultDir(), ttlMillis)
                    : new OfflineConfigCache(new com.fasterxml.jackson.databind.ObjectMapper(),
                            Paths.get(dir), ttlMillis));
        }
        return builder.build();
    }
}
