package org.apache.hadoop.hdfs.server.blockmanagement;

import static com.google.common.collect.Collections2.transform;
import static com.google.common.collect.Iterators.*;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Sets.newHashSet;
import static java.util.Collections.emptyList;
import static java.util.regex.Pattern.compile;

import com.google.common.base.Function;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.server.namenode.FSClusterStats;
import org.apache.hadoop.net.NetworkTopology;
import org.apache.hadoop.net.Node;
import pl.rtshadow.lem.benchmarks.hdfs.FileSystemService;

public class ColocateAppropriateBlocksOfTheSameGroupPolicy extends BlockPlacementPolicy {
  private final static Pattern MANAGED_FILES_DIRECTORY = compile("(.*managed/[^/]+/).+");

  private final BlockPlacementPolicy defaultPolicy;
  private final FileSystemService fileSystemService;

  private Configuration configuration;
  private NetworkTopology networkTopology;
  private DistributedFileSystem fileSystem;

  public ColocateAppropriateBlocksOfTheSameGroupPolicy() {
    this(new BlockPlacementPolicyDefault(), new FileSystemService());
  }

  public ColocateAppropriateBlocksOfTheSameGroupPolicy(BlockPlacementPolicy defaultPolicy, FileSystemService fileSystemService) {
    this.fileSystemService = fileSystemService;
    this.defaultPolicy = defaultPolicy;
  }

  @Override
  DatanodeDescriptor[] chooseTarget(String srcPath, int numOfReplicas, DatanodeDescriptor writer, List<DatanodeDescriptor> chosenNodes, long blocksize) {
    List<DatanodeDescriptor> possibleLocations = getPossibleLocationsFor(srcPath);
    if (!possibleLocations.isEmpty()) {
      return formPipeline(possibleLocations, chosenNodes, numOfReplicas);
    }
    return defaultPolicy.chooseTarget(srcPath, numOfReplicas, writer, chosenNodes, blocksize);
  }

  @Override
  public DatanodeDescriptor[] chooseTarget(String srcPath, int numOfReplicas, DatanodeDescriptor writer, List<DatanodeDescriptor> chosenNodes, boolean returnChosenNodes, HashMap<Node, Node> excludedNodes, long blocksize) {
    List<DatanodeDescriptor> possibleLocations = getPossibleLocationsFor(srcPath);
    if (!possibleLocations.isEmpty()) {
      return formPipeline(possibleLocations, chosenNodes, numOfReplicas);
    }
    return defaultPolicy.chooseTarget(srcPath, numOfReplicas, writer, chosenNodes, excludedNodes, blocksize);
  }

  @Override
  public int verifyBlockPlacement(String srcPath, LocatedBlock lBlk, int minRacks) {
    return defaultPolicy.verifyBlockPlacement(srcPath, lBlk, minRacks);
  }

  @Override
  public DatanodeDescriptor chooseReplicaToDelete(BlockCollection srcBC, Block block, short replicationFactor, Collection<DatanodeDescriptor> existingReplicas, Collection<DatanodeDescriptor> moreExistingReplicas) {
    throw new UnsupportedOperationException();
  }

  @Override
  protected void initialize(Configuration conf, FSClusterStats stats, NetworkTopology clusterMap) {
    this.configuration = conf;
    this.networkTopology = clusterMap;
    defaultPolicy.initialize(conf, stats, clusterMap);
  }

  private DistributedFileSystem getFileSystem() {
    if (fileSystem == null) {
      fileSystem = fileSystemService.getFileSystem(configuration);
    }
    return fileSystem;
  }

  private DatanodeDescriptor[] formPipeline(List<DatanodeDescriptor> desiredNodes, List<DatanodeDescriptor> chosenNodes, int numOfReplicas) {
    return toArray(limit(concat(desiredNodes.iterator(), chosenNodes.iterator()), numOfReplicas), DatanodeDescriptor.class);
  }

  private List<DatanodeDescriptor> getPossibleLocationsFor(String srcPath) {
    try {
      if (isManaged(srcPath)) {
        String fileGroupPath = extractFileGroupPath(srcPath);
        int nextBlockId = computeNumberOfNextBlock(srcPath);

        final Collection<String> possibleLocationsHosts = retrieveLocationsFor(listGroup(fileGroupPath), nextBlockId);

        return newArrayList(transform(possibleLocationsHosts, new Function<String, DatanodeDescriptor>() {
          @Override
          public DatanodeDescriptor apply(String location) {
            return (DatanodeDescriptor) networkTopology.getNode(location);
          }
        }));
      }
    } catch (IOException e) {
      throw new RuntimeException();
    }
    return emptyList();
  }

  private Collection<String> retrieveLocationsFor(FileStatus[] files, int blockId) throws IOException {
    Collection<String> blockLocations = newHashSet();
    for (FileStatus file : files) {
      BlockLocation[] blocks = getFileSystem().getFileBlockLocations(file, 0, Long.MAX_VALUE);
      if (blocks.length > blockId) {
        blockLocations.addAll(newArrayList(blocks[blockId].getTopologyPaths()));
      }
    }
    return blockLocations;
  }

  private int computeNumberOfNextBlock(String path) {
    try {
      FileStatus fileStatus = getFileSystem().getFileStatus(new Path(path));
      BlockLocation[] locations = getFileSystem().getFileBlockLocations(fileStatus, 0, Long.MAX_VALUE);
      return locations.length;
    } catch (IOException e) {
      return 0;
    }
  }

  private FileStatus[] listGroup(String fileGroupPath) throws IOException {
    return getFileSystem().listStatus(new Path(fileGroupPath));
  }

  private String extractFileGroupPath(String path) {
    Matcher matcher = MANAGED_FILES_DIRECTORY.matcher(path);
    matcher.matches();
    return matcher.group(1);
  }

  private boolean isManaged(String path) {
    return MANAGED_FILES_DIRECTORY.matcher(path).matches();
  }
}
