package com.github.irvs.ros_tms.tms_ur.tms_ur_mimamorukun;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.ActionBarDrawerToggle;
import android.support.v4.widget.DrawerLayout;
import android.util.Log;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.ros.address.InetAddressFactory;
import org.ros.android.RosActivity;
import org.ros.node.NodeConfiguration;
import org.ros.node.NodeMainExecutor;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

public class TmsUrMimamorukun extends RosActivity
{
    private String TAG = "tms_ur_mimamorukun";

    private TextView touch_position, touch_action, debug_info;
    private TextView ros_master_uri, local_ip;
    private TextView rp_cmd_status, dbreader_status;

    private WcIcon wc_icon;

    private ImageView map_image;
    private int mode = 0;
    private int[] map_offset = {0,0};
    private MapTouchListener mapTouchListener;

    private ActionBarDrawerToggle drawerToggle;
    private DrawerLayout drawer;

    private Handler handler;

    private Button execute_button;
    private Switch debug_switch, mode_switch;

    // conditional tag for handler
    public static final int UPDATE_POSITION = 0;
    public static final int RP_CMD_RESULT = 1;
    public static final int RP_CMD_STATUS = 2;
    public static final int DBREADER_STATUS = 3;

    // ros nodes
    private rp_cmd_client rp_cmd_client;
    private db_reader_client db_reader_client;

    final Context context = this;

    public class Pose {
        public double x;
        public double y;
        public double yaw;
    }

    public class WcIcon {
        public ImageView current, target;
        public float[] current_size = {0, 0};
        public float[] target_size = {0, 0};
        private Pose current_pose = new Pose();
        private Pose target_pose = new Pose();

        public WcIcon() {
            current = (ImageView)findViewById(R.id.current);
            target = (ImageView)findViewById(R.id.target);
            AssetManager as = getResources().getAssets();
            try {
                InputStream is = as.open("images/wc_icon.png");
                Bitmap bm = BitmapFactory.decodeStream(is);
                current.setImageBitmap(bm);
                current.setScaleX((float) 0.15);
                current.setScaleY((float) 0.15);
            } catch (IOException e) {
                Log.e(TAG, e.toString());
            }
            try {
                InputStream is = as.open("images/wc_icon2.png");
                Bitmap bm = BitmapFactory.decodeStream(is);
                target.setImageBitmap(bm);
                target.setScaleX((float)0.15);
                target.setScaleY((float) 0.15);
            } catch (IOException e){
                Log.e(TAG, e.toString());
            }
            current_size[0] = current.getDrawable().getIntrinsicWidth();
            current_size[1] = current.getDrawable().getIntrinsicHeight();
            target_size[0] = target.getDrawable().getIntrinsicWidth();
            target_size[1] = target.getDrawable().getIntrinsicHeight();
            target.setVisibility(View.INVISIBLE);
        }
    }

    public class MapTouchListener implements View.OnTouchListener {
        double touch_x, touch_y, orientation;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            Log.d(TAG, "X:" + event.getX() + ",Y:" + event.getY());
            touch_position.setText("X:" + event.getX() + ", Y:" + event.getY());
            if (mode == 0) {
                wc_icon.target_pose.x = event.getX();
                wc_icon.target_pose.y = event.getY();
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        Log.d(TAG, "getAction()" + "ACTION_DOWN");
                        touch_action.setText("ACTION_DOWN");
                        drawTarget();
                        break;
                    case MotionEvent.ACTION_UP:
                        Log.d(TAG, "getAction()" + "ACTION_UP");
                        touch_action.setText("ACTION_UP");
                        break;
                    case MotionEvent.ACTION_MOVE:
                        Log.d(TAG, "getAction()" + "ACTION_MOVE");
                        touch_action.setText("ACTION_MOVE");
                        drawTarget();
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        Log.d(TAG, "getAction()" + "ACTION_CANCEL");
                        touch_action.setText("ACTION_CANCEL");
                        break;
                }
            } else if (mode == 1) {
                touch_x = event.getX();
                touch_y = event.getY();
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        Log.d(TAG, "getAction()" + "ACTION_DOWN");
                        touch_action.setText("ACTION_DOWN");
                        rotateTarget();
                        break;
                    case MotionEvent.ACTION_UP:
                        Log.d(TAG, "getAction()" + "ACTION_UP");
                        touch_action.setText("ACTION_UP");
                        break;
                    case MotionEvent.ACTION_MOVE:
                        Log.d(TAG, "getAction()" + "ACTION_MOVE");
                        touch_action.setText("ACTION_MOVE");
                        rotateTarget();
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        Log.d(TAG, "getAction()" + "ACTION_CANCEL");
                        touch_action.setText("ACTION_CANCEL");
                        break;
                }
            }
            return true;
        }

        public void drawTarget() {
            wc_icon.target.setVisibility(View.VISIBLE);
            wc_icon.target.setX((float)wc_icon.target_pose.x - wc_icon.target_size[0] / 2 + map_offset[0]);
            wc_icon.target.setY((float)wc_icon.target_pose.y - wc_icon.target_size[1] / 2 + map_offset[1]);
            debug_info.setText("x: " + String.format("%.2f[m]\n", wc_icon.target_pose.x) +
                "y: " + String.format("%.2f[m]\n", wc_icon.target_pose.y) +
                "yaw: " + String.format("%.2f[deg]", wc_icon.target_pose.yaw));
        }

        public void rotateTarget() {
            touch_x -= wc_icon.target.getX() + wc_icon.target_size[0] / 2 - map_offset[0];
            touch_y -= wc_icon.target.getY() + wc_icon.target_size[1] / 2 - map_offset[1];
            orientation = Math.atan2(touch_y, touch_x);
            wc_icon.target.setRotation((float)(orientation*180/Math.PI) - 90);
            wc_icon.target_pose.yaw = wc_icon.target.getRotation();
            debug_info.setText("x: " + String.format("%.2f[m]\n", wc_icon.target_pose.x) +
                    "y: " + String.format("%.2f[m]\n", wc_icon.target_pose.y) +
                    "yaw: " + String.format("%.2f[deg]", wc_icon.target_pose.yaw));
        }
    }
    public TmsUrMimamorukun()
    {
        super("Mimamorukun Control", "Mimamorukun Control", URI.create("http://192.168.4.170:11311"));
    }

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        // for debug
        touch_position = (TextView)findViewById(R.id.touch_position);
        touch_position.setVisibility(View.INVISIBLE);
        touch_action = (TextView)findViewById(R.id.touch_action);
        touch_action.setVisibility(View.INVISIBLE);
        debug_info = (TextView)findViewById(R.id.debug_info);
        ros_master_uri  = (TextView)findViewById(R.id.ros_master_uri);
        local_ip  = (TextView)findViewById(R.id.local_ip);
        rp_cmd_status = (TextView)findViewById(R.id.rp_cmd_status);
        dbreader_status = (TextView)findViewById(R.id.dbreader_status);

        // the icon of a wheelchair
        wc_icon = new WcIcon();

        // the map of tms room
        map_image = (ImageView)findViewById(R.id.map_image);
        AssetManager as = getResources().getAssets();
        try {
            InputStream is = as.open("images/map_image.png");
            Bitmap bm = BitmapFactory.decodeStream(is);
            map_image.setImageBitmap(bm);
        } catch (IOException e) {
            Log.e(TAG, e.toString());
        }
        mapTouchListener = new MapTouchListener();
        map_image.setOnTouchListener(mapTouchListener);

        // navigation drawer
        drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        drawerToggle = new ActionBarDrawerToggle(this, drawer,
            org.ros.android.android_10.R.mipmap.icon, R.string.drawer_open,
            R.string.drawer_close) {
            @Override
            public void onDrawerClosed(View drawerView) {
                Log.d(TAG, "onDrawerClosed");
                map_image.setOnTouchListener(mapTouchListener);
            }

            @Override
            public void onDrawerOpened(View drawerView) {
                Log.d(TAG, "onDrawerOpened");
                map_image.setOnTouchListener(null);
            }

            @Override
            public void onDrawerSlide(View drawerView, float slideOffset) {
                super.onDrawerSlide(drawerView, slideOffset);
                Log.d(TAG, "onDrawerSlide : " + slideOffset);
                drawer.requestLayout();
            }

            @Override
            public void onDrawerStateChanged(int newState) {
                Log.i(TAG, "onDrawerStateChanged  new state : " + newState);
            }
        };
        drawer.setDrawerListener(drawerToggle);

        // handler
        handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                switch (msg.what) {
                    case UPDATE_POSITION: {
                        Log.d(TAG, "handleMessage/UPDATE_POSITION");
                        wc_icon.current_pose.x = db_reader_client.current_pose.x;
                        wc_icon.current_pose.y = db_reader_client.current_pose.y;
                        wc_icon.current_pose.yaw = db_reader_client.current_pose.yaw;
                        if(false) {
                            wc_icon.current.setX((float) wc_icon.current_pose.x - wc_icon.current_size[0] / 2 + map_offset[0]);
                            wc_icon.current.setY((float) wc_icon.current_pose.y - wc_icon.current_size[1] / 2 + map_offset[1]);
                            wc_icon.current.setRotation((float) wc_icon.current_pose.yaw);
                        } else {
                            wc_icon.current.setX(100);
                            wc_icon.current.setY(100);
                            wc_icon.current.setRotation(90);
                        }
                        break;
                    }
                    case RP_CMD_RESULT: {
                        Log.d(TAG, "handleMessage/RP_CMD_RESULT");
                        Toast.makeText(context, (String)msg.obj, Toast.LENGTH_SHORT).show();
                        break;
                    }
                    case RP_CMD_STATUS: {
                        rp_cmd_status.setText((String)msg.obj);
                        break;
                    }
                    case DBREADER_STATUS: {
                        dbreader_status.setText((String)msg.obj);
                        break;
                    }
                }
            }
        };

        // service execution
        execute_button = (Button)findViewById(R.id.execute_button);
        execute_button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG, "onClick");
                drawer.closeDrawers();
                //TODO ProgressDialogの追加
                rp_cmd_client.sendRequest(wc_icon.target_pose.x, wc_icon.target_pose.y, (wc_icon.target_pose.yaw * (float) Math.PI) / 180);
            }
        });

        WifiManager wifiManager = (WifiManager)getSystemService(WIFI_SERVICE);
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        int ip_i = wifiInfo.getIpAddress();
        String ip = ((ip_i >> 0) & 0xFF) + "." + ((ip_i >> 8) & 0xFF) + "." + ((ip_i >> 16) & 0xFF) + "." + ((ip_i >> 24) & 0xFF);
        local_ip.setText(ip);

        debug_switch = (Switch)findViewById(R.id.debug_switch);
        debug_switch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked == true) {
                    touch_action.setVisibility(View.VISIBLE);
                    touch_position.setVisibility(View.VISIBLE);
                } else {
                    touch_action.setVisibility(View.INVISIBLE);
                    touch_position.setVisibility(View.INVISIBLE);
                }
            }
        });

        mode_switch = (Switch)findViewById(R.id.mode_switch);
        mode_switch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked == true) {
                    mode = 1;
                } else {
                    mode = 0;
                }
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        Log.d(TAG, "onWindowFocusChanged()");
        super.onWindowFocusChanged(hasFocus);

        map_image.getLocationOnScreen(map_offset);
        Log.d(TAG, "map_offset x:" + map_offset[0] + ",y:" + map_offset[1]);

        final Rect rect = new Rect();
        Window window = super.getWindow();
        window.getDecorView().getWindowVisibleDisplayFrame(rect);
        Log.d(TAG, "statusbar height:" + rect.top);

        map_offset[1] -= rect.top;
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        drawerToggle.syncState();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        drawerToggle.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void init(NodeMainExecutor nodeMainExecutor) {
        NodeConfiguration nodeConfiguration
            = NodeConfiguration.newPublic(InetAddressFactory.newNonLoopback().getHostAddress());
        nodeConfiguration.setMasterUri(getMasterUri());

        ros_master_uri.setText(getMasterUri().toString());

        rp_cmd_client = new rp_cmd_client(handler);
        nodeMainExecutor.execute(rp_cmd_client, nodeConfiguration);

        db_reader_client = new db_reader_client(handler);
        nodeMainExecutor.execute(db_reader_client, nodeConfiguration);
    }
}
