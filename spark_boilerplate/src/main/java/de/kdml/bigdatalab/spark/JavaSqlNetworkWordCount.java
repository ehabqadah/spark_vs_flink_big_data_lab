package de.kdml.bigdatalab.spark;

import java.util.Arrays;
import java.util.Iterator;
import java.util.regex.Pattern;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.VoidFunction2;
import org.apache.spark.sql.AnalysisException;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.api.java.StorageLevels;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.Time;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaReceiverInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;

/**
 * Use DataFrames and SQL to count words in UTF8 encoded, '\n' delimited text
 * received from the network every second.
 *
 * Usage: JavaSqlNetworkWordCount <hostname> <port> <hostname> and <port>
 * describe the TCP server that Spark Streaming would connect to receive data.
 *
 * To run this on your local machine, you need to first run a Netcat server `$
 * nc -lk 9999` and then run the example `$ bin/run-example
 * org.apache.spark.examples.streaming.JavaSqlNetworkWordCount localhost 9999`
 */

public final class JavaSqlNetworkWordCount {
	private static final Pattern SPACE = Pattern.compile(" ");

	public static void main(String[] args) throws Exception {

		// Create the context with a 1 second batch size
		SparkConf sparkConf = new SparkConf().setAppName("JavaSqlNetworkWordCount");
		JavaStreamingContext ssc = new JavaStreamingContext(sparkConf, Durations.seconds(1));

		// Create a JavaReceiverInputDStream on target ip:port and count the
		// words in input stream of \n delimited text (eg. generated by 'nc')
		// Note that no duplication in storage level only for running locally.
		// Replication necessary in distributed scenario for fault tolerance.
		JavaReceiverInputDStream<String> lines = ssc.socketTextStream("localhost", 9999,
				StorageLevels.MEMORY_AND_DISK_SER);
		JavaDStream<String> words = lines.flatMap(new FlatMapFunction<String, String>() {
			@Override
			public Iterator<String> call(String x) {
				return Arrays.asList(SPACE.split(x)).iterator();
			}
		});

		// Convert RDDs of the words DStream to DataFrame and run SQL query
		words.foreachRDD(new VoidFunction2<JavaRDD<String>, Time>() {
			@Override
			public void call(JavaRDD<String> rdd, Time time) {
				SparkSession spark = JavaSparkSessionSingleton.getInstance(rdd.context().getConf());

				// Convert JavaRDD[String] to JavaRDD[bean class] to DataFrame
				JavaRDD<JavaRecord> rowRDD = rdd.map(new Function<String, JavaRecord>() {
					@Override
					public JavaRecord call(String word) {
						JavaRecord record = new JavaRecord();
						record.setWord(word);
						return record;
					}
				});
				Dataset<Row> wordsDataFrame = spark.createDataFrame(rowRDD, JavaRecord.class);

				// Creates a temporary view using the DataFrame
				try {
					wordsDataFrame.createTempView("words");
				} catch (AnalysisException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

				// Do word count on table using SQL and print it
				Dataset<Row> wordCountsDataFrame = spark.sql("select word, count(*) as total from words group by word");
				System.out.println("========= " + time + "=========");
				wordCountsDataFrame.show();
			}
		});

		ssc.start();
		ssc.awaitTermination();
	}
}

/** Lazily instantiated singleton instance of SparkSession */
class JavaSparkSessionSingleton {
	private static transient SparkSession instance = null;

	public static SparkSession getInstance(SparkConf sparkConf) {
		if (instance == null) {
			instance = SparkSession.builder().config(sparkConf).getOrCreate();
		}
		return instance;
	}
}
