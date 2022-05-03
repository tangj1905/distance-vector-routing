# Project 3 - Distance Vector Routing

---

Running this program should be relatively straightforward. After compilation, the server can be run using `java Server <port-number>`, and the client program can be run using `java Client <host-address> <host-port>`. The server will wait until there are enough clients to satisfy the network as specified in the configuration file; by default, the DV algorithm will start once 6 clients join the server (corresponding to the network specified in the assignment document).

The configuration file can be found in `src/config/config.csv`, and contains the costs between each pair of nodes. It should be represented as a symmetric square matrix with zeros on the main diagonal, corresponding to the fact that costs should be symmetric (the cost from node A to node B should be the same both ways), and the cost from a node to itself is zero. The program is configured to run the example network that's specified in the assignment document, but this can be changed freely to any network configuration of your choice (provided that it meets the above criteria). Any negative cost indicates that there is no direct connection between the two nodes.

**Author:** jgt31 \
**Date:** May 2, 2022
