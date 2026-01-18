package com.example.translationlayer.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for discovering SMB shares on the local network using mDNS/Bonjour.
 * Discovers NAS devices that advertise themselves via _smb._tcp.
 */
@Service
public class NasDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(NasDiscoveryService.class);

    // SMB service type for mDNS discovery
    private static final String SMB_SERVICE_TYPE = "_smb._tcp.local.";

    private JmDNS jmdns;
    private final Map<String, DiscoveredNas> discoveredDevices = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    @PostConstruct
    public void init() {
        // Initialize in background to avoid slowing down startup
        Thread initThread = new Thread(() -> {
            try {
                InetAddress addr = InetAddress.getLocalHost();
                jmdns = JmDNS.create(addr);
                jmdns.addServiceListener(SMB_SERVICE_TYPE, new SmbServiceListener());
                initialized = true;
                log.info("NAS discovery service started, listening for SMB shares");
            } catch (IOException e) {
                log.warn("Could not initialize NAS discovery: {}", e.getMessage());
            }
        }, "nas-discovery-init");
        initThread.setDaemon(true);
        initThread.start();
    }

    @PreDestroy
    public void shutdown() {
        if (jmdns != null) {
            try {
                jmdns.close();
                log.info("NAS discovery service stopped");
            } catch (IOException e) {
                log.warn("Error closing mDNS: {}", e.getMessage());
            }
        }
    }

    /**
     * Get all discovered NAS devices.
     */
    public List<DiscoveredNas> getDiscoveredDevices() {
        return new ArrayList<>(discoveredDevices.values());
    }

    /**
     * Trigger a refresh of discovered devices.
     */
    public void refresh() {
        if (jmdns != null && initialized) {
            // Request service info for all known services
            ServiceInfo[] services = jmdns.list(SMB_SERVICE_TYPE, 3000);
            for (ServiceInfo info : services) {
                processServiceInfo(info);
            }
        }
    }

    /**
     * Check if discovery service is ready.
     */
    public boolean isReady() {
        return initialized;
    }

    private void processServiceInfo(ServiceInfo info) {
        if (info == null)
            return;

        String name = info.getName();
        String host = info.getHostAddresses().length > 0 ? info.getHostAddresses()[0] : null;
        int port = info.getPort();

        if (host != null && !host.isEmpty()) {
            DiscoveredNas nas = new DiscoveredNas(name, host, port > 0 ? port : 445);
            discoveredDevices.put(name, nas);
            log.debug("Discovered NAS: {} at {}", name, host);
        }
    }

    private class SmbServiceListener implements ServiceListener {

        @Override
        public void serviceAdded(ServiceEvent event) {
            log.debug("SMB service found: {}", event.getName());
            // Request more info about the service
            jmdns.requestServiceInfo(event.getType(), event.getName(), true);
        }

        @Override
        public void serviceRemoved(ServiceEvent event) {
            String name = event.getName();
            discoveredDevices.remove(name);
            log.debug("SMB service removed: {}", name);
        }

        @Override
        public void serviceResolved(ServiceEvent event) {
            processServiceInfo(event.getInfo());
        }
    }

    /**
     * Represents a discovered NAS device.
     */
    public record DiscoveredNas(String name, String host, int port) {
    }
}
