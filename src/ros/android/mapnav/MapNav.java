/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package ros.android.mapnav;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Menu;
import android.widget.Toast;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.RadioButton;
import android.graphics.Color;

import org.ros.node.Node;
import org.ros.node.service.ServiceResponseListener;
import org.ros.exception.RosException;
import org.ros.exception.RemoteException;
import org.ros.node.service.ServiceClient;
import org.ros.message.map_store.MapListEntry;
import org.ros.namespace.NameResolver;
import org.ros.service.map_store.ListLastMaps;
import org.ros.service.map_store.PublishMap;

import ros.android.activity.RosAppActivity;
import ros.android.views.SensorImageView;
import ros.android.views.SetInitialPoseDisplay;
import ros.android.views.SendGoalDisplay;
import ros.android.views.PathDisplay;
import ros.android.views.MapView;
import ros.android.views.MapDisplay;
import ros.android.views.JoystickView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;

/**
 * @author kwc@willowgarage.com (Ken Conley)
 * @author hersh@willowgarage.com (Dave Hershberger)
 */
public class MapNav extends RosAppActivity implements MapDisplay.MapDisplayStateCallback {
  private SensorImageView cameraView;
  private MapView mapView;
  private JoystickView joystickView;
  private ViewGroup mainLayout;
  private ViewGroup sideLayout;
  private String robotAppName;
  private String cameraTopic;
  private SetInitialPoseDisplay poseSetter;
  private SendGoalDisplay goalSender;
  private PathDisplay pathDisplay;
  private ProgressDialog progress;
  private RadioButton goalButton;
  private RadioButton poseButton;

  private enum ViewMode {
    CAMERA, MAP
  };

  private ViewMode viewMode;
  private boolean deadman;
  private boolean poseSet;

  private ProgressDialog waitingDialog;
  private AlertDialog chooseMapDialog;
  
  @Override
  public void onMapDisplayState(final MapDisplay.State state) {
    runOnUiThread(new Runnable() {
        public void run() {
          if (MapNav.this.progress != null) {
            MapNav.this.progress.dismiss();
            MapNav.this.progress = null;
          }
          
          if (state == MapDisplay.State.STATE_STARTING) {
            //Create spinning progress dialog
            MapNav.this.progress = ProgressDialog.show(MapNav.this, "Loading...", "Waiting for map from robot...", true, false);
            MapNav.this.progress.setProgressStyle(ProgressDialog.STYLE_SPINNER);
          } else if (state == MapDisplay.State.STATE_LOADING) {
            //Create spinning progress dialog
            MapNav.this.progress = ProgressDialog.show(MapNav.this, "Loading map...", "Displaying map...", true, false);
            MapNav.this.progress.setProgressStyle(ProgressDialog.STYLE_SPINNER);
          } else if (state == MapDisplay.State.STATE_NEED_MAP) {
            //Need map
            MapNav.this.progress = ProgressDialog.show(MapNav.this, "Waiting for map selection...", "Waiting for the map selection service...", true, false);
            MapNav.this.progress.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            new Thread(new Runnable() {
                @Override public void run() {
                  if (MapNav.this.waitForService(20)) {
                    if (MapNav.this.progress != null) {
                      MapNav.this.progress.dismiss();
                      MapNav.this.progress = null;
                    }
                    MapNav.this.readAvailableMapList();
                  } else {
                    Log.e("MapNav", "Failed to get the map service");
                    runOnUiThread(new Runnable() {
                        public void run() {
                          if (MapNav.this.progress != null) {
                            MapNav.this.progress.dismiss();
                            MapNav.this.progress = null;
                          }
                          android.os.Process.killProcess(android.os.Process.myPid());
                        }});
                  }
                }}).start();
          } else if (state == MapDisplay.State.STATE_WORKING) {
            //Closed dialog - do nothing
          } else {
            Log.e("MapNav", "Invalid state: " + state);
          }
        }});
  }


  /** Called when the activity is first created. */
  @Override
  public void onCreate(Bundle savedInstanceState) {
    setDefaultAppName("turtlebot_teleop/android_map_nav");
    setDashboardResource(R.id.top_bar);
    setMainWindowResource(R.layout.main);
    super.onCreate(savedInstanceState);
    joystickView = (JoystickView)findViewById(R.id.joystick);
    cameraView = (SensorImageView) findViewById(R.id.image);
    // cameraView.setOnTouchListener(this);
    if (getIntent().hasExtra("base_control_topic")) {
      joystickView.setBaseControlTopic(getIntent().getStringExtra("base_control_topic"));
    }
    if (getIntent().hasExtra("camera_topic")) {
      cameraTopic = getIntent().getStringExtra("camera_topic");
    } else {
      cameraTopic = "camera/rgb/image_color/compressed_throttle";
    }
    mapView = (MapView) findViewById(R.id.map_view);
    if (getIntent().hasExtra("footprint_param")) {
      mapView.setFootprintParam(getIntent().getStringExtra("footprint_param"));
    }
    if (getIntent().hasExtra("base_scan_topic")) {
      mapView.setBaseScanTopic(getIntent().getStringExtra("base_scan_topic"));
    }
    if (getIntent().hasExtra("base_scan_frame")) {
      mapView.setBaseScanFrame(getIntent().getStringExtra("base_scan_frame"));
    }
    mapView.addMapDisplayCallback(this);
    
    mainLayout = (ViewGroup) findViewById(R.id.main_layout);
    sideLayout = (ViewGroup) findViewById(R.id.side_layout);
    
    viewMode = ViewMode.CAMERA;
    
    mapView.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        MapNav.this.swapViews();
      }
    });
    cameraView.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        MapNav.this.swapViews();
      }
    });
    mapView.setClickable(true);
    cameraView.setClickable(false);

    poseSetter = new SetInitialPoseDisplay();
    poseSetter.disable();
    mapView.addDisplay( poseSetter );
    mapView.getPoser().addPosable( "/map", "/base_footprint", poseSetter );

    goalSender = new SendGoalDisplay();
    goalSender.disable();
    mapView.addDisplay( goalSender );
    mapView.getPoser().addPosable( "/map", "/base_footprint", goalSender );

    pathDisplay = new PathDisplay();
    mapView.addDisplay( pathDisplay );
    if (getIntent().hasExtra("path_topic")) {
      pathDisplay.setTopic(getIntent().getStringExtra("path_topic"));
    }
    pathDisplay.setColor(Color.rgb(0, 255, 0));
    //mapView.getPoser().addPosable( "/map", "/map", pathDisplay );
    
    goalButton = (RadioButton) findViewById(R.id.set_goal_button);
    poseButton = (RadioButton) findViewById(R.id.set_pose_button);
    poseButton.toggle();
    setPose();
  }

  public void setPoseClicked(View view) {
    setPose();
  }

  public void setGoalClicked(View view) {
    setGoal();
  }

  public void placePosition(View view) {
    if (poseSet) {
      poseSetter.placeOnClick();
    } else {
      goalSender.placeOnClick();
    }
  }

  /**
   * Swap the camera and map views.
   */
  private void swapViews() {
    // Figure out where the views were...
    ViewGroup mapViewParent;
    ViewGroup cameraViewParent;
    Log.i("MapNav", "viewMode = " + viewMode);
    if (viewMode == ViewMode.CAMERA) {
      Log.i("MapNav", "camera mode");
      mapViewParent = sideLayout;
      cameraViewParent = mainLayout;
    } else {
      Log.i("MapNav", "map mode");
      mapViewParent = mainLayout;
      cameraViewParent = sideLayout;
    }
    int mapViewIndex = mapViewParent.indexOfChild(mapView);
    int cameraViewIndex = cameraViewParent.indexOfChild(cameraView);

    // Remove the views from their old locations...
    mapViewParent.removeView(mapView);
    cameraViewParent.removeView(cameraView);

    // Add them to their new location...
    mapViewParent.addView(cameraView, mapViewIndex);
    cameraViewParent.addView(mapView, cameraViewIndex);

    // Remeber that we are in the other mode now.
    if (viewMode == ViewMode.CAMERA) {
      viewMode = ViewMode.MAP;
    } else {
      viewMode = ViewMode.CAMERA;
    }
    mapView.setClickable(viewMode != ViewMode.MAP);
    cameraView.setClickable(viewMode != ViewMode.CAMERA);
  }

  @Override
  protected void onNodeDestroy(Node node) {
    deadman = false;
    if (cameraView != null) {
      cameraView.stop();
      cameraView = null;
    }
    if (joystickView != null) {
      joystickView.stop();
      joystickView = null;
    }
    mapView.stop();
    poseSetter.stop();
    goalSender.stop();
    super.onNodeDestroy(node);
  }

  @Override
  protected void onResume() {
    super.onResume();
    Toast.makeText(MapNav.this, "starting app", Toast.LENGTH_LONG).show();
  }

  @Override
  protected void onNodeCreate(Node node) {
    Log.i("MapNav", "startAppFuture");
    super.onNodeCreate(node);
    if( appManager != null ) {
      try {
        mapView.start(node);
        NameResolver appNamespace = getAppNamespace(node);
        cameraView = (SensorImageView) findViewById(R.id.image);
        Log.i("MapNav", "init cameraView");
        cameraView.start(node, appNamespace.resolve(cameraTopic).toString());
        cameraView.post(new Runnable() {
            @Override
            public void run() {
              cameraView.setSelected(true);
            }});
        Log.i("MapNav", "init twistPub");
        joystickView.start(node);
        poseSetter.start(node);
        goalSender.start(node);
      } catch (RosException ex) {
        safeToastStatus( "Failed: " + ex.getMessage() );
      }
    } else {
      safeToastStatus( "App Manager failed to start." );
    }
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    MenuInflater inflater = getMenuInflater();
    inflater.inflate(R.menu.mapnav_options, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
    case R.id.kill:
      android.os.Process.killProcess(android.os.Process.myPid());
      return true;
    case R.id.set_pose:
      setPose();
      return true;
    case R.id.set_goal:
      setGoal();
      return true;
    case R.id.choose_map:
      readAvailableMapList();
      return true;
    default:
      return super.onOptionsItemSelected(item);
    }
  }

  private void setPose() {
    poseSetter.enable();
    goalSender.disable();
    poseSet = true;
  }

  private void setGoal() {
    goalSender.enable();
    poseSetter.disable();
    poseSet = false;
  }

  private boolean waitForService(int n) {
    int i = 0;
    while (i < n) {
      try {
        ServiceClient<ListLastMaps.Request, ListLastMaps.Response> listMapsServiceClient =
          getNode().newServiceClient("list_last_maps", "map_store/ListLastMaps");
        return true;
      } catch (Throwable ex) {
        Log.i("MapNav", "Wait again for map list");
        //Do nothing.
        try {
          Thread.sleep(1000L);
        } catch(java.lang.InterruptedException iex) {
          return false;
        }
      }
      i++;
    }
    return false;
  }

  private void readAvailableMapList() {
    safeShowWaitingDialog("Waiting for map list");
    Thread mapLoaderThread = new Thread(new Runnable() {
        @Override public void run() {
          try {
	    ServiceClient<ListLastMaps.Request, ListLastMaps.Response> listMapsServiceClient =
              getNode().newServiceClient("list_last_maps", "map_store/ListLastMaps");
            listMapsServiceClient.call(new ListLastMaps.Request(), new ServiceResponseListener<ListLastMaps.Response>() {
                @Override public void onSuccess(ListLastMaps.Response message) {
                  Log.i("MapNav", "readAvailableMapList() Success");
                  safeDismissWaitingDialog();
                  showMapListDialog(message.map_list);
                }
                @Override public void onFailure(RemoteException e) {
                  Log.i("MapNav", "readAvailableMapList() Failure");
                  safeToastStatus("Reading map list failed: " + e.getMessage());
                  safeDismissWaitingDialog();
                }
              });
          } catch(Throwable ex) {
            Log.e("MapNav", "readAvailableMapList() caught exception.", ex);
            safeToastStatus("Listing maps couldn't even start: " + ex.getMessage());
            safeDismissWaitingDialog();
          }
        }
      });
    mapLoaderThread.start();
  }

  /**
   * Show a dialog with a list of maps.  Safe to call from any thread.
   */
  private void showMapListDialog(final ArrayList<MapListEntry> mapList) {
    // Make an array of map name/date strings.
    final CharSequence[] availableMapNames = new CharSequence[mapList.size()];
    for( int i = 0; i < mapList.size(); i++ ) {
      String displayString;
      String name = mapList.get(i).name;
      Date creationDate = new Date(mapList.get(i).date * 1000);
      String dateTime = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(creationDate);
      if( name != null && ! name.equals("") ) {
        displayString = name + " " + dateTime;
      } else {
        displayString = dateTime;
      }
      availableMapNames[i] = displayString;
    }

    runOnUiThread(new Runnable() {
        @Override public void run() {
          AlertDialog.Builder builder = new AlertDialog.Builder(MapNav.this);
          builder.setTitle("Choose a map");
          builder.setItems(availableMapNames, new DialogInterface.OnClickListener() {
              @Override public void onClick(DialogInterface dialog, int itemIndex) {
                loadMap( mapList.get( itemIndex ));
              }
            });
          chooseMapDialog = builder.create();
          chooseMapDialog.show();
        }
      });
  }

  private void loadMap( MapListEntry mapListEntry ) {
    Log.i("MapNav", "loadMap(): " + mapListEntry.name);
    safeShowWaitingDialog("Loading map");
    try {
      ServiceClient<PublishMap.Request, PublishMap.Response> publishMapServiceClient =
        getNode().newServiceClient("publish_map", "map_store/PublishMap");
      PublishMap.Request req = new PublishMap.Request();
      req.map_id = mapListEntry.map_id;
      publishMapServiceClient.call(req, new ServiceResponseListener<PublishMap.Response>() {
          @Override public void onSuccess(PublishMap.Response message) {
            Log.i("MapNav", "loadMap() Success");
            safeDismissWaitingDialog();
            poseSetter.enable();
          }
          @Override public void onFailure(RemoteException e) {
            Log.i("MapNav", "loadMap() Failure");
            safeToastStatus("Loading map failed: " + e.getMessage());
            safeDismissWaitingDialog();
          }
        });
      mapView.resetMapDisplayState();
    } catch(Throwable ex) {
      Log.e("MapNav", "loadMap() caught exception.", ex);
      safeToastStatus("Publishing map couldn't even start: " + ex.getMessage());
      safeDismissWaitingDialog();
    }
  }

  private void safeDismissChooseMapDialog() {
    runOnUiThread(new Runnable() {
        @Override public void run() {
          if( chooseMapDialog != null ) {
            chooseMapDialog.dismiss();
            chooseMapDialog = null;
          }
        }
      });
  }

  private void safeShowWaitingDialog(final CharSequence message) {
    runOnUiThread(new Runnable() {
        @Override public void run() {
          waitingDialog = ProgressDialog.show(MapNav.this, "", message, true);
        }
      });
  }

  private void safeDismissWaitingDialog() {
    runOnUiThread(new Runnable() {
        @Override public void run() {
          if( waitingDialog != null ) {
            waitingDialog.dismiss();
            waitingDialog = null;
          }
        }
      });
  }
}
