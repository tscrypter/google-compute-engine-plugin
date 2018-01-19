package com.google.jenkins.plugins.computeengine.client;

import com.google.api.services.compute.Compute;
import com.google.api.services.compute.model.*;
import com.google.inject.matcher.Matchers;
import com.google.jenkins.plugins.computeengine.AcceleratorConfiguration;
import com.google.jenkins.plugins.computeengine.ComputeEngineCloud;
import com.google.jenkins.plugins.computeengine.InstanceConfiguration;
import hudson.model.Node;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import javax.imageio.ImageTypeSpecifier;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@RunWith(MockitoJUnitRunner.class)
public class InstanceConfigurationTest {
    static final String PROJECT_ID = "test-project";
    static final String REGION = "us-west1";
    static final String ZONE = "us-west1-a";
    static final String LABEL = "LABEL1, LABEL2";
    static final String MACHINE_TYPE = "n1-standard-1";
    static final String CONFIG_DESC = "test-config";
    static final String BOOT_DISK_TYPE = "pd-standard";
    static final String BOOT_DISK_AUTODELETE_STR = "false";
    static final String BOOT_DISK_IMAGE_NAME = "test-image";
    static final String BOOT_DISK_PROJECT_ID = PROJECT_ID;
    static final String BOOT_DISK_SIZE_GB_STR = "10";
    static final Node.Mode NODE_MODE = Node.Mode.EXCLUSIVE;
    static final String ACCELERATOR_NAME = "test-gpu";
    static final String ACCELERATOR_COUNT = "1";
    public final String NETWORK_NAME = "test-network";
    public final String SUBNETWORK_NAME = "test-subnetwork";


    @Mock
    public ComputeClient computeClient;

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Before
    public void init() throws Exception {
        List<Region> regions = new ArrayList<Region>();
        regions.add(new Region().setName(REGION).setSelfLink(REGION));
        List<Zone> zones = new ArrayList<Zone>();
        zones.add(new Zone().setName(ZONE).setSelfLink(ZONE));
        List<MachineType> machineTypes = new ArrayList<MachineType>();
        machineTypes.add(new MachineType().setName(MACHINE_TYPE).setSelfLink(MACHINE_TYPE));
        List<DiskType> diskTypes = new ArrayList<DiskType>();
        diskTypes.add(new DiskType().setName(BOOT_DISK_TYPE).setSelfLink(BOOT_DISK_TYPE));
        List<Image> imageTypes = new ArrayList<Image>();
        imageTypes.add(new Image().setName(BOOT_DISK_IMAGE_NAME).setSelfLink(BOOT_DISK_IMAGE_NAME));
        List<Network> networks = new ArrayList<Network>();
        networks.add(new Network().setName(NETWORK_NAME).setSelfLink(NETWORK_NAME));
        List<Subnetwork> subnetworks = new ArrayList<Subnetwork>();
        subnetworks.add(new Subnetwork().setName(SUBNETWORK_NAME).setSelfLink(SUBNETWORK_NAME));
        List<AcceleratorType> acceleratorTypes = new ArrayList<AcceleratorType>();
        acceleratorTypes.add(new AcceleratorType().setName(ACCELERATOR_NAME).setSelfLink(ACCELERATOR_NAME).setMaximumCardsPerInstance(Integer.parseInt(ACCELERATOR_COUNT)));

        Mockito.when(computeClient.getRegions()).thenReturn(regions);
        Mockito.when(computeClient.getZones(anyString())).thenReturn(zones);
        Mockito.when(computeClient.getMachineTypes(anyString())).thenReturn(machineTypes);
        Mockito.when(computeClient.getBootDiskTypes(anyString())).thenReturn(diskTypes);
        Mockito.when(computeClient.getImages(anyString())).thenReturn(imageTypes);
        Mockito.when(computeClient.getAcceleratorTypes(anyString())).thenReturn(acceleratorTypes);
        //Mockito.when(computeClient.getNetworks(anyString())).thenReturn(networks);
        //Mockito.when(computeClient.getSubnetworks(anyString(), anyString())).thenReturn(subnetworks);
        //Mockito.when(computeClient.getSubnetworks(anyString(), anyString(), anyString())).thenReturn(subnetworks);

        computeClient.setProjectId(PROJECT_ID);
    }

    @Test
    public void testClient() throws Exception {
        List<Region> regions = computeClient.getRegions();
        assert (regions.size() == 1);
        assert (regions.get(0).getName().equals(REGION));

        List<Zone> zones = computeClient.getZones(REGION);
        assert (zones.size() == 1);
        assert (zones.get(0).getName().equals(ZONE));
        assert (zones.get(0).getSelfLink().equals(ZONE));
    }

    @Test
    public void testConfigRoundtrip() throws Exception {

        InstanceConfiguration want = new InstanceConfiguration(
                REGION,
                ZONE,
                MACHINE_TYPE,
                LABEL,
                CONFIG_DESC,
                BOOT_DISK_TYPE,
                BOOT_DISK_AUTODELETE_STR,
                BOOT_DISK_IMAGE_NAME,
                BOOT_DISK_PROJECT_ID,
                BOOT_DISK_SIZE_GB_STR,
                NODE_MODE,
                new AcceleratorConfiguration(ACCELERATOR_NAME, ACCELERATOR_COUNT));

        InstanceConfiguration.DescriptorImpl.setComputeClient(computeClient);
        AcceleratorConfiguration.DescriptorImpl.setComputeClient(computeClient);

        List<InstanceConfiguration> configs = new ArrayList<InstanceConfiguration>();
        configs.add(want);

        ComputeEngineCloud gcp = new ComputeEngineCloud("test", PROJECT_ID, "testCredentialsId", "1", configs);
        r.jenkins.clouds.add(gcp);

        r.submit(r.createWebClient().goTo("configure").getFormByName("config"));
        InstanceConfiguration got = ((ComputeEngineCloud) r.jenkins.clouds.iterator().next()).getInstanceConfig(CONFIG_DESC);
        r.assertEqualBeans(want, got, "region,zone,machineType,bootDiskType,bootDiskSourceImageName,bootDiskSourceImageProject,bootDiskSizeGb,acceleratorConfiguration");
    }

}