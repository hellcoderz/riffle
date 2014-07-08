package riffle.hadoop;

import org.apache.hadoop.fs.*;
import org.apache.hadoop.util.*;
import org.apache.hadoop.conf.Configuration;
import java.io.*;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedList;
import org.apache.hadoop.mapreduce.*;
import org.apache.hadoop.io.*;
import clojure.java.api.Clojure;
import clojure.lang.IFn;


public class RiffleMergeJob {

    // Fake "input format" that just gives generated data
    public static class PathInputFormat extends InputFormat<IntWritable,Text> {

        public static class EmptySplit extends InputSplit implements Writable {
            public void write(DataOutput out) throws IOException { }

            public void readFields(DataInput in) throws IOException { }

            public long getLength() { return 0L; }

            public String[] getLocations() { return new String[0]; }
        }

        public static class PathTuple {
            public int key;
            public String value;

            public PathTuple(int key, String value) {
                this.key = key;
                this.value = value;
            }
        }

        private static List<List<String>> _pathLists;
        private static int _numShards;

        public static void setNumShards(Job job, int numShards) {
            _numShards = numShards;
        }

        public static void setPaths(Job job, List<List<String>> paths) {
            _pathLists = paths;
        }

        public PathInputFormat() {
        }

        public List<InputSplit> getSplits(JobContext jobContext) {
            List<InputSplit> ret = new ArrayList<InputSplit>();
            int numSplits = jobContext.getConfiguration().getInt(MRJobConfig.NUM_MAPS, 1);
            for (int i = 0; i < numSplits; ++i) {
                ret.add(new EmptySplit());
            }
            return ret;

        }

        public RecordReader<IntWritable,Text> createRecordReader(InputSplit ignored, TaskAttemptContext taskContext) throws IOException {

            return new RecordReader<IntWritable,Text>() {

                private LinkedList<PathTuple> _paths;
                private int _numPaths;

                private IntWritable _key;
                private Text _value;

                public void initialize(InputSplit split, TaskAttemptContext context) {
                    _paths = new LinkedList();
                    for (List<String> l : _pathLists) {
                        for (int i = 0; i < l.size(); i++) {
                            _paths.add(new PathTuple((int)(((float)i/l.size())*_numShards), l.get(i)));
                        }
                    }
                    _numPaths = _paths.size();
                }

                public boolean nextKeyValue() {
                    if (_paths.isEmpty()) {
                        _key = null;
                        _value = null;
                        return false;
                    }
                    _key = new IntWritable(_paths.peek().key);
                    _value = new Text(_paths.peek().value);
                    _paths.pop();
                    return true;
                }

                public IntWritable getCurrentKey() {
                    return _key;
                }

                public Text getCurrentValue() {
                    return _value;
                }

                public void close() throws IOException { }

                public float getProgress() throws IOException {
                    return (float) (_numPaths - _paths.size()) / _numPaths;
                }
            };
        }
    }

    // Partitioner
    public static class Partitioner extends org.apache.hadoop.mapreduce.Partitioner<IntWritable, Text> {

        public Partitioner() {
        }

        public int getPartition(IntWritable key, Text value, int numPartitions) {
            return key.get();
        }
    }

    //Reducer
    public static class Reducer extends org.apache.hadoop.mapreduce.Reducer<IntWritable, Text, BytesWritable, BytesWritable> {

        private static int _numShards;

        public void setNumShard(int numShards) {
            _numShards = numShards;
        }

        private IFn _mergedSeqFn;
        private FileSystem _fs;

        public Reducer() throws IOException {
            IFn require = Clojure.var("clojure.core", "require");
            require.invoke(Clojure.read("riffle.hadoop.utils"));

            _mergedSeqFn = Clojure.var("riffle.hadoop.utils", "merged-kvs");

            _fs = FileSystem.get(new Configuration());
        }

        protected void cleanup(org.apache.hadoop.mapreduce.Reducer.Context context) {
        }

        protected void reduce(IntWritable key, Iterable<Text> values, Context context) throws IOException, InterruptedException {
            List<String> paths = new ArrayList<String>();
            for(Text val : values) {
                paths.add(val.toString());
            }

            for (List<byte[]> l : (List<List<byte[]>>)_mergedSeqFn.invoke(key.get(), new Integer(_numShards), _fs, paths)) {
                context.write(new BytesWritable(l.get(0)), new BytesWritable(l.get(1)));
            }
        }
    }
}
