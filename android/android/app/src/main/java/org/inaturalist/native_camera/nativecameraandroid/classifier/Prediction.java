package org.inaturalist.native_camera.nativecameraandroid.classifier;

public class Prediction {
    public Node node;
    public Double probability;
    public Double score;
    public int rank;

    public Prediction(Node n, double p) {
        node = n;
        probability = p;
    }
}

