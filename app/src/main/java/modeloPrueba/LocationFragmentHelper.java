/*
 * Copyright 2011 Giles Malet.
 * Modified 2013 Wilson Brenna.
 *
 * This file is part of GTFSOffline.
 * 
 * GTFSOffline is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * GTFSOffline is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with GTFSOffline.  If not, see <http://www.gnu.org/licenses/>.
 */

package modeloPrueba;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.location.Location;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.text.format.Time;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ProgressBar;
import android.widget.Toast;

public class LocationFragmentHelper {
	
	private static final String TAG = "LocationFragmentHelper";
	private static String FAVSTOPS_KEY;

	private static int grid_size;
	private static int NUM_CLOSEST_STOPS;
	private static int NUM_BUSES;	//the number of next buses per stop to be shown.
    private boolean USE_ROUTE_NO;

	private Location mLocation;
	private timestopdescArrayAdapter mAdapter;
	private ArrayList<String[]> mListDetails;

	private Context mContext;
	
	private String myDBName;
	private SQLiteDatabase myDB;
	private DatabaseHelper mDatabaseHelper;

	// Need to store some stuff in an array, so we can sort by distance
	class StopLocn {
		public float dist, bearing;
		public double lat, lon;
		public String stop_id, stop_name;
	}
	private StopLocn[] mStops;
	private SharedPreferences mPrefs;
	private static boolean ampmflag;
	private static int hoursLookAhead;
	
	private ProgressBar mProgress;
	


	public LocationFragmentHelper(Context context, String aDBName, 
				SQLiteDatabase aDB, ProgressBar aProgress) {
		mContext = context;
		myDBName = aDBName;
		myDB = aDB;
	
		mDatabaseHelper = new DatabaseHelper(mContext);
		
		// Load animations used to show/hide progress bar
		//mTitle = (TextView) findViewById(R.id.listtitle);
		mProgress = aProgress;
		mListDetails = new ArrayList<String[]>(NUM_CLOSEST_STOPS*NUM_BUSES);

		//mTitle.setText(R.string.loading_stops);
		mStops = null;
		
		//set up prefs
		FAVSTOPS_KEY = new String(mContext.getString(R.string.pref_favstops_key));
		mPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
		reloadPreferences();
	}
	
	public void runProcessOnLocation(Location aLocation) {
		mLocation = aLocation;
		new ProcessBusStops().execute();
	}

	private void reloadPreferences() {
		ampmflag = mPrefs.getBoolean(mContext.getString(R.string.pref_ampmtimes_key), false);
		NUM_CLOSEST_STOPS = Integer.parseInt(mPrefs.getString(
											mContext.getString(R.string.pref_num_closest_stops), "8"));
		NUM_BUSES = Integer.parseInt(mPrefs.getString(
								mContext.getString(R.string.pref_num_buses_per_stop), "3"));
		hoursLookAhead = Integer.parseInt(mPrefs.getString(
					mContext.getString(R.string.pref_hours_look_ahead), "1"));
		grid_size = Integer.parseInt(mPrefs.getString(
				mContext.getString(R.string.pref_grid_size_key), "1"));
        USE_ROUTE_NO = mPrefs.getBoolean("useroutenos", false);
	}

	public ArrayList<String[]> retrieveNextBusList() {
		return mListDetails;
	}
	
	public void addTimeAdapter(timestopdescArrayAdapter anAdapter) {
		mAdapter = anAdapter;
	}

	/* Do the processing to load the ArrayAdapter for display. */
	public class ProcessBusStops extends AsyncTask<Void, Integer, Void> {
		// static final String TAG = "ProcessBusStops";

		
		@Override
		protected void onPreExecute() {
//			mListDetail.startAnimation(mSlideIn);
			mProgress.setVisibility(View.VISIBLE);	
		}

		// Update the progress bar.
		@Override
		protected void onProgressUpdate(Integer... parms) {
			mProgress.setProgress(parms[0]);
		}

		@Override
		protected Void doInBackground(Void... foo) {
			// Log.v(TAG, "doInBackground()");

			if( myDB == null ) {
				myDB = mDatabaseHelper.ReadableDB(myDBName, myDB);
			}
			if (myDB == null) {
				//we tried!
				return null;
			}
			if (mLocation == null) {
				//uh oh, no location yet!
				//we'll trust the TOAST in LocationHelper to keep people aware of this.
				return null;
			}
			double myLongitude = mLocation.getLongitude();
			double myLatitude = mLocation.getLatitude();
			double myTop = myLatitude + (180.0/Math.PI)*(grid_size/6378.1370);
			double myBottom = myLatitude - (180.0/Math.PI)*(grid_size/6378.1370);
			double myLeft = myLongitude - (180.0/Math.PI)*(grid_size/6378.1370)/Math.cos(Math.PI/180.0*myLatitude);
			double myRight = myLongitude + (180.0/Math.PI)*(grid_size/6378.1370)/Math.cos(Math.PI/180.0*myLatitude);

			final String qry;
			final String[] selectargs;
			if ( grid_size == 0 ) {
				//we want to look as far as possible away.
				qry = "select stop_id as _id, stop_lat, stop_lon, stop_name from stops";
				selectargs = new String[] { };
			} else {
				qry = "select stop_id as _id, stop_lat, stop_lon, stop_name from stops " +
						"where stop_lat < ? and stop_lat > ? and stop_lon < ? and stop_lon > ?";
				selectargs = new String[] { Double.toString(myTop), Double.toString(myBottom), 
					Double.toString(myLeft), Double.toString(myRight) };
			}
			int maxcount;

			
			// Load the stops from the database the first time through
			if (mStops == null) {
				final Cursor csr = mDatabaseHelper.ReadableDB(myDBName, myDB).rawQuery(qry, selectargs);
				maxcount = csr.getCount();
				mStops = new StopLocn[maxcount];
				boolean more = csr.moveToPosition(0);
				int locidx = 0;

				while (more) {
					// stash in array
					mStops[locidx] = new StopLocn();
					mStops[locidx].stop_id = csr.getString(0);
					mStops[locidx].lat = csr.getDouble(1);
					mStops[locidx].lon = csr.getDouble(2);
					mStops[locidx].stop_name = csr.getString(3);

					more = csr.moveToNext();
					++locidx;
					//publishProgress(((int) ((locidx / (float) maxcount) * 100)));
				}
				csr.close();
			}
			
			// Calculate the distance to each point in the array
			final float[] results = new float[2];
			
			if(mLocation == null) {
				return null;
			}
			for (final StopLocn s : mStops) {

				Location.distanceBetween(mLocation.getLatitude(), mLocation.getLongitude(), s.lat, s.lon, results);
				s.dist = results[0];
				s.bearing = results[1];
			}

			// Sort by distance from our current location
			Arrays.sort(mStops, new Comparator<StopLocn>() {
				@Override
				public int compare(StopLocn entry1, StopLocn entry2) {
					return entry1.dist < entry2.dist ? -1 : entry1.dist == entry2.dist ? 0 : 1;
				}
			});

			// Transfer everything to an array list to load in display
			// Bearing is from -180 to +180, so use as index into here
			mListDetails.clear();
			final String[] DIRS = { "S", "SW", "W", "NW", "N", "NE", "E", "SE", };
			Integer stop_limit;
			if (mStops.length < NUM_CLOSEST_STOPS) {
				stop_limit = mStops.length;
			} else {
				stop_limit = NUM_CLOSEST_STOPS;
			}
			
			final Time t = new Time();
			t.setToNow();
			
			for (int i = 0; i < stop_limit; i++) {
				final StopLocn s = mStops[i];

				final String dir = DIRS[(int) (s.bearing + 180 + 22.5) % 360 / 45];
				String dist;
				if (s.dist < 1000) {
					dist = String.format("%3.0fm %s", s.dist, dir);
				} else {
					dist = String.format("%3.1fkm %s", s.dist / 1000.0, dir);
				}
				//So, we have the heading of the nearest stop. Now, we need to query to find
				//the next NUM_BUSES.
				ServiceCalendar myBusService = new ServiceCalendar(myDBName, myDB, ampmflag);
				myBusService.setDB(mDatabaseHelper);
				final ArrayList<String[]> fullResultsA = myBusService.getNextDepartureTimes(t, s.stop_id, 
						NUM_BUSES, hoursLookAhead, true);
				//the format of this:
				// departuretime	runstoday	trip_id		route_short_name	trip_headsign
				//	140300				1		34867		13					Route 13 Laurelwood
				
				ArrayList<String[]> fullResults = myBusService.getNextDepartureTimes(t, s.stop_id, 
						NUM_BUSES, hoursLookAhead, false);

				if ((fullResults == null) && (fullResultsA == null))
				{
					if (stop_limit < mStops.length - 1) {
						stop_limit++;
					}
					continue;
				} else if (fullResultsA == null) {
					//do nothing
				} else if (fullResults == null) {
					fullResults = fullResultsA;
				}
				else {
                    if (t.hour <= hoursLookAhead) {
                        fullResults.addAll(fullResultsA);
                    }
                    else {
                        ArrayList<String[]> tmpResults = fullResultsA;
                        tmpResults.addAll(fullResults);
                        fullResults = tmpResults;
                    }
				}
				
				for (String[] str: fullResults) {
					//process the result string to get the right departure time
					final String hours = str[0].substring(0,2);
					final String minutes = str[0].substring(2,4);
					//String departsIn;
                    final String routeNo;
                    if (str[3].equals("") || (!USE_ROUTE_NO)) {
                        routeNo = s.stop_id;
                    }
                    else {
                        routeNo = str[3];
                    }

					mListDetails.add(new String[] { dist, s.stop_id, s.stop_name, 
							str[4], myBusService.formattedDepartureTime(t, hours, minutes),
							str[2], myDBName, routeNo});
					
				}
				publishProgress(((int) ((i / (float) stop_limit) * 100)));
			}
			
			return null;
		}

		@Override
		protected void onCancelled() {
			mDatabaseHelper.CloseDB(mDatabaseHelper.ReadableDB(myDBName, myDB));
		}
		
		@Override
		protected void onPostExecute(Void foo) {
			 //Log.v(TAG, "onPostExecute(), closing " + myDBName );

			mProgress.setVisibility(View.INVISIBLE);
			//mListDetail.startAnimation(mSlideOut);

			//mTitle.setText(R.string.title_activity_closest_stops);
			if(mAdapter != null) {
				mAdapter.notifyDataSetChanged();
			}
			//close the database
			mDatabaseHelper.CloseDB(myDB);
			myDB = null;
		}
	}
	
	// Called for a long click
	public void onListItemLongClick(AdapterView<?> parent, View v, int position, long id) {
		Log.v(TAG, "long clicked position " + position);

		final String[] strs = (String[]) parent.getItemAtPosition(position);
		if (strs == null) {
			return;
		}
		final String stop_id = strs[1];
		final String stop_name = strs[2];

		final DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int id) {
				switch (id) {
				case DialogInterface.BUTTON_POSITIVE:
					AddBusstopFavourite(stop_id, stop_name + "#" + myDBName);
					break;
				}
				dialog.cancel();
			}
		};

		final AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
		builder.setTitle("Stop " + stop_id + ", " + stop_name);
		builder.setMessage(R.string.favs_add_to_list).setPositiveButton(R.string.yes, listener)
		.setNegativeButton(R.string.no, listener).create().show();
	}
	
	public ArrayList<String[]> GetBusstopFavourites() {
		final String favs = mPrefs.getString(FAVSTOPS_KEY, "");

		// Load the array for the list
		final ArrayList<String[]> details = new ArrayList<String[]>();

		// favs is a semi-colon separated string of stops, with a trailing semi-colon.
		// Then each stop has a description stored as KEY-stop.
		if (!favs.equals("")) {
			final TextUtils.StringSplitter splitter = new TextUtils.SimpleStringSplitter(';');
			splitter.setString(favs);
			for (final String s : splitter) {
				final String[] strs = { s, mPrefs.getString(FAVSTOPS_KEY + "-" + s, "") };
				details.add(strs);
			}
		}
		return details;
	}


	public void AddBusstopFavourite(String busstop, String stopname) {
		String favs = mPrefs.getString(FAVSTOPS_KEY, "");

		final TextUtils.StringSplitter splitter = new TextUtils.SimpleStringSplitter(';');
		splitter.setString(favs);
		boolean already = false;
		for (final String s : splitter) {
			if (s.equals(busstop)) {
				already = true;
				break;
			}
		}

		if (already) {
			Toast.makeText(mContext, "Stop " + busstop + " is already a favourite!", Toast.LENGTH_LONG).show();
		} else {
			favs += busstop + ";";
			mPrefs.edit().putString(FAVSTOPS_KEY, favs).putString(FAVSTOPS_KEY + "-" + busstop, stopname).commit();
			Toast.makeText(mContext, "Stop " + busstop + " was added to your favourites.", Toast.LENGTH_LONG).show();
		}
	}
	
	
}
